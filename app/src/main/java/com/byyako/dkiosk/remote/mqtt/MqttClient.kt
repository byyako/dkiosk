package com.byyako.dkiosk.remote.mqtt

import com.byyako.dkiosk.recovery.Backoff
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.concurrent.thread

/**
 * Keeps one connection to an MQTT broker, reconnecting with backoff until [stop]. Messages to
 * publish while disconnected are dropped; the listener's onConnected republishes what matters.
 * Nothing here blocks the caller: socket work happens on the client's own threads.
 */
class MqttClient(private val config: Config, private val listener: Listener) {

    class Config(
        val host: String,
        val port: Int,
        val tls: Boolean,
        val username: String?,
        val password: String?,
        val clientId: String,
        val will: MqttPackets.Will?,
        val subscriptions: List<String>,
        val keepAliveSec: Int = 30,
    )

    interface Listener {
        /** Called on the client's thread once connected and subscribed. */
        fun onConnected()

        /** Called on the client's thread. */
        fun onMessage(topic: String, payload: ByteArray)

        /** A short description of the connection state, for the settings screen. */
        fun onStatus(status: String)
    }

    private val writer: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { Thread(it, "mqtt-writer") }
    private val lock = Any()
    private var socket: Socket? = null
    private var output: OutputStream? = null

    @Volatile private var running = false
    private var loop: Thread? = null

    fun start() {
        check(!running && loop == null) { "Make a new client to restart" }
        running = true
        loop = thread(name = "mqtt", isDaemon = true) { run() }
        writer.scheduleWithFixedDelay(
            { send(MqttPackets.PINGREQ) },
            config.keepAliveSec / 2L, config.keepAliveSec / 2L, TimeUnit.SECONDS,
        )
    }

    /** Publishes [lastWords] (like an "offline" availability message) if connected, then disconnects. */
    fun stop(lastWords: Pair<String, ByteArray>? = null) {
        running = false
        try {
            writer.execute {
                if (lastWords != null) write(MqttPackets.publish(lastWords.first, lastWords.second, retain = true))
                write(MqttPackets.DISCONNECT)
                closeSocket()
            }
        } catch (_: RejectedExecutionException) {
            // Already stopped.
        }
        writer.shutdown()
        loop?.interrupt()
    }

    fun publish(topic: String, payload: ByteArray, retain: Boolean) {
        send(MqttPackets.publish(topic, payload, retain))
    }

    private fun send(packet: ByteArray) {
        try {
            writer.execute { write(packet) }
        } catch (_: RejectedExecutionException) {
            // Stopped.
        }
    }

    private fun write(packet: ByteArray) {
        val out = synchronized(lock) { output } ?: return
        try {
            out.write(packet)
            out.flush()
        } catch (_: IOException) {
            closeSocket() // The read loop notices and reconnects.
        }
    }

    private fun run() {
        val backoff = Backoff(firstMs = 2_000, maxMs = 60_000)
        while (running) {
            try {
                listener.onStatus(STATUS_CONNECTING)
                session { backoff.reset() }
            } catch (e: IOException) {
                if (running) listener.onStatus(e.message ?: e.javaClass.simpleName)
            } finally {
                closeSocket()
            }
            if (!running) break
            try {
                Thread.sleep(backoff.nextDelayMs())
            } catch (_: InterruptedException) {
                break
            }
        }
        listener.onStatus(STATUS_STOPPED)
    }

    /** One connection, from opening the socket until it drops. */
    private fun session(onConnected: () -> Unit) {
        val plain = Socket()
        plain.connect(InetSocketAddress(config.host, config.port), CONNECT_TIMEOUT_MS)
        val socket = if (config.tls) secure(plain) else plain
        // Pings go out every half keep-alive; hearing nothing for longer than that means it's gone.
        socket.soTimeout = config.keepAliveSec * 1500
        val input = socket.getInputStream()
        val out = socket.getOutputStream()
        synchronized(lock) { this.socket = socket } // So stop() can close it.
        if (!running) return

        // The handshake writes directly; the writer thread only gets the stream once it's done, so
        // a ping or publish can't land in the middle of it.
        out.write(MqttPackets.connect(config.clientId, config.keepAliveSec, config.username, config.password, config.will))
        out.flush()
        val code = try {
            MqttPackets.connackCode(MqttPackets.read(input, MAX_PACKET))
        } catch (e: SocketTimeoutException) {
            throw IOException("The broker didn't answer", e)
        }
        if (code != 0) throw IOException(MqttPackets.connackReason(code))
        if (config.subscriptions.isNotEmpty()) {
            out.write(MqttPackets.subscribe(1, config.subscriptions))
            out.flush()
        }
        synchronized(lock) { if (this.socket === socket) output = out }
        onConnected()
        listener.onStatus(STATUS_CONNECTED)
        listener.onConnected()

        while (running) {
            val packet = MqttPackets.read(input, MAX_PACKET)
            if (packet.type == MqttPackets.PUBLISH) {
                val message = MqttPackets.message(packet)
                if (message.qos == 1) send(MqttPackets.puback(message.packetId))
                listener.onMessage(message.topic, message.payload)
            }
            // CONNACK, SUBACK and PINGRESP need no answer; reading them is enough to keep alive.
        }
    }

    /** Wraps [plain] in TLS, checking the certificate is for the broker's name like a browser would. */
    private fun secure(plain: Socket): Socket {
        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        val secure = factory.createSocket(plain, config.host, config.port, true) as SSLSocket
        secure.soTimeout = CONNECT_TIMEOUT_MS
        secure.startHandshake()
        if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(config.host, secure.session)) {
            secure.close()
            throw IOException("The broker's certificate isn't for ${config.host}")
        }
        return secure
    }

    private fun closeSocket() {
        val open = synchronized(lock) {
            val current = socket
            socket = null
            output = null
            current
        }
        runCatching { open?.close() }
    }

    companion object {
        const val STATUS_CONNECTING = "Connecting"
        const val STATUS_CONNECTED = "Connected"
        const val STATUS_STOPPED = "Stopped"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val MAX_PACKET = 64 * 1024
    }
}
