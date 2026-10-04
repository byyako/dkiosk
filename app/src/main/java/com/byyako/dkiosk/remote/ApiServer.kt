package com.byyako.dkiosk.remote

import android.util.Log
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Serves [KioskApi] on [port] on every network interface. Once stopped, make a new one to start again. */
class ApiServer(val port: Int, private val api: KioskApi) {

    // Two workers and a short queue: plenty for a few automations, and a flood can't pile up forever.
    private val workers = ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(16))
    private var socket: ServerSocket? = null
    private val lock = Any()
    private val clients = mutableSetOf<Socket>()
    private var stopped = false

    /** The selected port when started with 0, useful for local integration tests. */
    internal val boundPort: Int get() = synchronized(lock) { socket?.localPort ?: port }

    /** Throws if the port can't be opened, e.g. because another app is using it. */
    fun start() {
        val server = synchronized(lock) {
            check(!stopped && socket == null) { "Make a new server to restart" }
            ServerSocket(port).also { socket = it }
        }
        thread(name = "api-server", isDaemon = true) {
            while (!server.isClosed) {
                val client = try {
                    server.accept()
                } catch (_: IOException) {
                    break // closed by stop()
                }
                val accepted = synchronized(lock) {
                    if (stopped) false else clients.add(client)
                }
                if (!accepted) {
                    client.close()
                    continue
                }
                try {
                    workers.execute { serve(client) }
                } catch (_: RejectedExecutionException) {
                    client.close()
                    synchronized(lock) { clients.remove(client) }
                }
            }
        }
    }

    fun stop() {
        val sockets = synchronized(lock) {
            stopped = true
            val open: List<java.io.Closeable> = listOfNotNull(socket) + clients.toList()
            socket = null
            clients.clear()
            open
        }
        // shutdownNow drops queued tasks without running their use/finally blocks.
        // Close accepted sockets ourselves, including active reads and queued clients.
        sockets.forEach { runCatching { it.close() } }
        workers.shutdownNow()
    }

    private fun serve(client: Socket) {
        try {
            client.use {
                it.soTimeout = 5_000
                val response = try {
                    api.handle(HttpRequest.read(it.getInputStream()))
                } catch (e: BadRequestException) {
                    HttpResponse.error(400, e.message.orEmpty())
                } catch (e: IOException) {
                    return@use
                } catch (e: Exception) {
                    Log.w(TAG, "API request failed", e)
                    HttpResponse.error(500, "Internal error")
                }
                response.write(it.getOutputStream())
            }
        } catch (_: IOException) {
            // Timed out, disconnected or closed by stop().
        } finally {
            synchronized(lock) { clients.remove(client) }
        }
    }

    private companion object {
        const val TAG = "ApiServer"
    }
}
