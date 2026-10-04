package com.byyako.dkiosk.remote.mqtt

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/**
 * The parts of MQTT 3.1.1 a kiosk needs: connect with a last will, publish and receive QoS 0
 * messages, subscribe, ping. QoS 1 messages a broker sends anyway are acknowledged.
 */
object MqttPackets {

    const val CONNACK = 2
    const val PUBLISH = 3
    const val PUBACK = 4
    const val SUBACK = 9
    const val PINGRESP = 13

    class Will(val topic: String, val payload: ByteArray, val retain: Boolean)

    class Packet(val type: Int, val flags: Int, val body: ByteArray)

    class Message(val topic: String, val payload: ByteArray, val qos: Int, val packetId: Int)

    fun connect(clientId: String, keepAliveSec: Int, username: String?, password: String?, will: Will?): ByteArray {
        val body = ByteArrayOutputStream()
        body.writeString("MQTT")
        body.write(4) // protocol level 3.1.1
        var flags = 0x02 // clean session
        if (will != null) flags = flags or 0x04 or (if (will.retain) 0x20 else 0)
        if (username != null) flags = flags or 0x80
        if (password != null) flags = flags or 0x40
        body.write(flags)
        body.writeShort(keepAliveSec)
        body.writeString(clientId)
        if (will != null) {
            body.writeString(will.topic)
            body.writeField(will.payload)
        }
        username?.let { body.writeString(it) }
        password?.let { body.writeString(it) }
        return packet(1, 0, body.toByteArray())
    }

    fun publish(topic: String, payload: ByteArray, retain: Boolean): ByteArray {
        val body = ByteArrayOutputStream()
        body.writeString(topic)
        body.write(payload)
        return packet(PUBLISH, if (retain) 1 else 0, body.toByteArray())
    }

    fun subscribe(packetId: Int, filters: List<String>): ByteArray {
        val body = ByteArrayOutputStream()
        body.writeShort(packetId)
        filters.forEach {
            body.writeString(it)
            body.write(0) // QoS 0
        }
        return packet(8, 2, body.toByteArray())
    }

    fun puback(packetId: Int): ByteArray = packet(PUBACK, 0, byteArrayOf((packetId shr 8).toByte(), packetId.toByte()))

    val PINGREQ: ByteArray = byteArrayOf(0xC0.toByte(), 0)
    val DISCONNECT: ByteArray = byteArrayOf(0xE0.toByte(), 0)

    /** Reads one packet, refusing bodies over [maxBytes] so a broker can't make us allocate without limit. */
    fun read(input: InputStream, maxBytes: Int): Packet {
        val first = input.read()
        if (first < 0) throw EOFException("Connection closed")
        var length = 0
        var multiplier = 1
        for (i in 0 until 4) {
            val byte = input.read()
            if (byte < 0) throw EOFException("Connection closed")
            length += (byte and 0x7F) * multiplier
            if (byte and 0x80 == 0) break
            if (i == 3) throw IOException("Malformed packet length")
            multiplier *= 128
        }
        if (length > maxBytes) throw IOException("Packet of $length bytes is too large")
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val count = input.read(body, read, length - read)
            if (count < 0) throw EOFException("Connection closed")
            read += count
        }
        return Packet(first shr 4, first and 0x0F, body)
    }

    /** The CONNACK return code: 0 is accepted. */
    fun connackCode(packet: Packet): Int {
        if (packet.type != CONNACK || packet.body.size != 2) throw IOException("Expected CONNACK")
        return packet.body[1].toInt() and 0xFF
    }

    fun message(packet: Packet): Message {
        val body = packet.body
        if (body.size < 2) throw IOException("Malformed PUBLISH")
        val topicLength = ((body[0].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF)
        var offset = 2 + topicLength
        if (offset > body.size) throw IOException("Malformed PUBLISH")
        val topic = String(body, 2, topicLength, Charsets.UTF_8)
        val qos = (packet.flags shr 1) and 0x03
        var packetId = 0
        if (qos > 0) {
            if (offset + 2 > body.size) throw IOException("Malformed PUBLISH")
            packetId = ((body[offset].toInt() and 0xFF) shl 8) or (body[offset + 1].toInt() and 0xFF)
            offset += 2
        }
        return Message(topic, body.copyOfRange(offset, body.size), qos, packetId)
    }

    fun connackReason(code: Int): String = when (code) {
        1 -> "The broker doesn't support MQTT 3.1.1"
        2 -> "The broker rejected the client ID"
        3 -> "The broker is unavailable"
        4 -> "Wrong username or password"
        5 -> "Not authorized"
        else -> "Connection refused ($code)"
    }

    private fun packet(type: Int, flags: Int, body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write((type shl 4) or flags)
        var length = body.size
        do {
            var byte = length % 128
            length /= 128
            if (length > 0) byte = byte or 0x80
            out.write(byte)
        } while (length > 0)
        out.write(body)
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.writeShort(value: Int) {
        write((value shr 8) and 0xFF)
        write(value and 0xFF)
    }

    private fun ByteArrayOutputStream.writeField(bytes: ByteArray) {
        require(bytes.size <= 0xFFFF) { "Field too long" }
        writeShort(bytes.size)
        write(bytes)
    }

    private fun ByteArrayOutputStream.writeString(value: String) = writeField(value.toByteArray(Charsets.UTF_8))
}
