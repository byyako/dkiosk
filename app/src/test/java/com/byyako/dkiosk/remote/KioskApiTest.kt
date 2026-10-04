package com.byyako.dkiosk.remote

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KioskApiTest {

    private class FakeKiosk : KioskControl {
        val calls = mutableListOf<String>()
        var openProblem: String? = null

        override fun status(): JSONObject = JSONObject().put("screen", "on")

        override fun reload() {
            calls += "reload"
        }

        override fun goHome() {
            calls += "home"
        }

        override fun open(url: String, makeHome: Boolean): String? {
            calls += "open $url home=$makeHome"
            return openProblem
        }

        override fun setScreen(on: Boolean) {
            calls += "screen $on"
        }
    }

    private val kiosk = FakeKiosk()
    private val api = KioskApi("secret-token", kiosk)

    private fun call(
        method: String,
        path: String,
        body: String = "",
        auth: String? = "Bearer secret-token",
    ): HttpResponse {
        val authHeader = auth?.let { "Authorization: $it\r\n" }.orEmpty()
        val raw = "$method $path HTTP/1.1\r\n${authHeader}Content-Length: ${body.toByteArray().size}\r\n\r\n$body"
        return api.handle(HttpRequest.read(raw.byteInputStream()))
    }

    @Test
    fun rejectsMissingOrWrongTokens() {
        assertEquals(401, call("GET", "/status", auth = null).status)
        assertEquals(401, call("GET", "/status", auth = "Bearer wrong").status)
        assertEquals(401, call("GET", "/status", auth = "Bearer ").status)
        assertEquals(401, call("GET", "/status", auth = "Basic c2VjcmV0LXRva2Vu").status)
        assertEquals(401, call("GET", "/status", auth = "secret-token").status)
        assertTrue(kiosk.calls.isEmpty())
    }

    @Test
    fun status() {
        val response = call("GET", "/status")
        assertEquals(200, response.status)
        assertEquals("on", response.body.getString("screen"))
    }

    @Test
    fun bearerSchemeIsCaseInsensitive() {
        assertEquals(200, call("GET", "/status", auth = "bearer secret-token").status)
    }

    @Test
    fun simpleCommands() {
        assertEquals(200, call("POST", "/reload").status)
        assertEquals(200, call("POST", "/home").status)
        assertEquals(listOf("reload", "home"), kiosk.calls)
    }

    @Test
    fun openUrl() {
        assertEquals(200, call("POST", "/url", """{"url": "example.com/dash"}""").status)
        assertEquals(200, call("POST", "/url", """{"url": "http://10.0.0.5:8123", "home": true}""").status)
        assertEquals(
            listOf("open https://example.com/dash home=false", "open http://10.0.0.5:8123 home=true"),
            kiosk.calls,
        )
    }

    @Test
    fun openUrlErrors() {
        assertEquals(400, call("POST", "/url", """{"url": "ftp://example.com"}""").status)
        assertEquals(400, call("POST", "/url", """{}""").status)
        assertEquals(400, call("POST", "/url", "not json").status)

        kiosk.openProblem = "example.com isn't an allowed site"
        val refused = call("POST", "/url", """{"url": "https://example.com"}""")
        assertEquals(403, refused.status)
        assertEquals("example.com isn't an allowed site", refused.body.getString("error"))
    }

    @Test
    fun screen() {
        assertEquals(200, call("POST", "/screen", """{"state": "off"}""").status)
        assertEquals(200, call("POST", "/screen", """{"state": "on"}""").status)
        assertEquals(400, call("POST", "/screen", """{"state": "dim"}""").status)
        assertEquals(listOf("screen false", "screen true"), kiosk.calls)
    }

    @Test
    fun wrongMethodOrPath() {
        assertEquals(405, call("POST", "/status").status)
        assertEquals(405, call("GET", "/reload").status)
        assertEquals(404, call("GET", "/nope").status)
        assertTrue(kiosk.calls.isEmpty())
    }

    @Test
    fun tokensAreRandomAndUrlSafe() {
        val first = KioskApi.newToken()
        assertNotEquals(first, KioskApi.newToken())
        assertEquals(32, first.length)
        assertTrue(first.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }
}
