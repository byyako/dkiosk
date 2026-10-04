package com.byyako.dkiosk.remote.mqtt

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class MqttPacketsTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun connectWithCredentialsAndWill() {
        val will = MqttPackets.Will("k/availability", "offline".toByteArray(), retain = true)
        val packet = MqttPackets.connect("dkiosk-1", 30, "user", "pw", will)
        val expectedBody = bytes(0, 4, 'M'.code, 'Q'.code, 'T'.code, 'T'.code, 4, 0xE6, 0, 30) +
            bytes(0, 8) + "dkiosk-1".toByteArray() +
            bytes(0, 14) + "k/availability".toByteArray() +
            bytes(0, 7) + "offline".toByteArray() +
            bytes(0, 4) + "user".toByteArray() +
            bytes(0, 2) + "pw".toByteArray()
        assertArrayEquals(bytes(0x10, expectedBody.size) + expectedBody, packet)
    }

    @Test
    fun connectWithoutCredentials() {
        val packet = MqttPackets.connect("c", 60, null, null, null)
        assertEquals(0x02, packet[9].toInt()) // clean session only
    }

    @Test
    fun publishRetainedAndRoundTrip() {
        val packet = MqttPackets.publish("a/b", "hi".toByteArray(), retain = true)
        assertArrayEquals(bytes(0x31, 7, 0, 3, 'a'.code, '/'.code, 'b'.code, 'h'.code, 'i'.code), packet)

        val read = MqttPackets.read(packet.inputStream(), 1024)
        val message = MqttPackets.message(read)
        assertEquals("a/b", message.topic)
        assertEquals("hi", String(message.payload))
        assertEquals(0, message.qos)
    }

    @Test
    fun readsQos1PublishWithPacketId() {
        val raw = bytes(0x32, 7, 0, 1, 't'.code, 0x12, 0x34, 'o'.code, 'k'.code)
        val message = MqttPackets.message(MqttPackets.read(raw.inputStream(), 1024))
        assertEquals("t", message.topic)
        assertEquals(1, message.qos)
        assertEquals(0x1234, message.packetId)
        assertEquals("ok", String(message.payload))
        assertArrayEquals(bytes(0x40, 2, 0x12, 0x34), MqttPackets.puback(0x1234))
    }

    @Test
    fun multiByteLengths() {
        val payload = ByteArray(300) { 'x'.code.toByte() }
        val packet = MqttPackets.publish("t", payload, retain = false)
        // 2 + 1 + 300 = 303 = 0xAF 0x02
        assertArrayEquals(bytes(0x30, 0xAF, 0x02), packet.copyOfRange(0, 3))
        val message = MqttPackets.message(MqttPackets.read(packet.inputStream(), 1024))
        assertEquals(300, message.payload.size)
    }

    @Test
    fun subscribe() {
        assertArrayEquals(
            bytes(0x82, 8, 0, 1, 0, 3, 'a'.code, '/'.code, '#'.code, 0),
            MqttPackets.subscribe(1, listOf("a/#")),
        )
    }

    @Test
    fun connack() {
        assertEquals(0, MqttPackets.connackCode(MqttPackets.read(bytes(0x20, 2, 0, 0).inputStream(), 16)))
        assertEquals(5, MqttPackets.connackCode(MqttPackets.read(bytes(0x20, 2, 0, 5).inputStream(), 16)))
    }

    @Test(expected = IOException::class)
    fun refusesOversizedPackets() {
        MqttPackets.read(bytes(0x30, 0xAF, 0x02).inputStream(), 100)
    }

    @Test(expected = IOException::class)
    fun refusesTruncatedPackets() {
        MqttPackets.read(bytes(0x30, 5, 0, 1).inputStream(), 100)
    }

    @Test(expected = IOException::class)
    fun refusesMalformedPublish() {
        MqttPackets.message(MqttPackets.Packet(3, 0, bytes(0, 9, 'a'.code)))
    }
}
