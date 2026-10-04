package com.byyako.dkiosk.remote

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.Socket
import java.net.SocketTimeoutException

class ApiServerTest {
    private val control = object : KioskControl {
        override fun status() = JSONObject()
        override fun reload() = Unit
        override fun goHome() = Unit
        override fun open(url: String, makeHome: Boolean, seconds: Int?): String? = null
        override fun setScreen(on: Boolean) = Unit
        override fun setBrightness(percent: Int?) = Unit
        override fun setVolume(percent: Int) = Unit
        override fun speak(text: String, language: String?): String? = null
        override fun playSound(url: String?) = Unit
        override fun showMessage(text: String, seconds: Int) = Unit
        override fun screenshot(): ByteArray? = null
    }

    @Test fun stopClosesActiveAndQueuedConnections() {
        val server = ApiServer(0, KioskApi("test-token", control))
        val clients = mutableListOf<Socket>()
        try {
            server.start()
            // Two incomplete requests occupy workers; sixteen more fill the queue. The next
            // connection is rejected, proving the preceding connections have been accepted.
            repeat(18) { clients += Socket("127.0.0.1", server.boundPort) }
            Socket("127.0.0.1", server.boundPort).use {
                it.soTimeout = 2_000
                assertTrue(closedByPeer(it))
            }
            server.stop()
            clients.forEach {
                it.soTimeout = 1_000
                assertTrue("A connection remained open after stop", closedByPeer(it))
            }
            server.stop() // Stopping twice is safe.
        } finally {
            server.stop()
            clients.forEach { it.close() }
        }
    }

    @Test fun servesAuthenticatedStatusOverSocket() {
        val server = ApiServer(0, KioskApi("test-token", control))
        try {
            server.start()
            Socket("127.0.0.1", server.boundPort).use {
                it.soTimeout = 2_000
                it.getOutputStream().write("GET /status HTTP/1.1\r\nAuthorization: Bearer test-token\r\n\r\n".toByteArray())
                assertEquals("HTTP/1.1 200 OK", it.getInputStream().bufferedReader().readLine())
            }
        } finally {
            server.stop()
        }
    }

    private fun closedByPeer(socket: Socket): Boolean = try {
        socket.getInputStream().read() == -1
    } catch (_: SocketTimeoutException) {
        false
    } catch (_: IOException) {
        true // Some platforms reset rather than cleanly close the connection.
    }
}
