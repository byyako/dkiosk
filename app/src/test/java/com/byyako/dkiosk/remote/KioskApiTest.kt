package com.byyako.dkiosk.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KioskApiTest {

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
    fun temporaryUrl() {
        assertEquals(200, call("POST", "/url", """{"url": "http://cam.local/door", "seconds": 30}""").status)
        assertEquals(listOf("open http://cam.local/door home=false for 30"), kiosk.calls)
        assertEquals(400, call("POST", "/url", """{"url": "http://cam.local", "seconds": 0}""").status)
        assertEquals(400, call("POST", "/url", """{"url": "http://cam.local", "seconds": 3601}""").status)
        assertEquals(400, call("POST", "/url", """{"url": "http://cam.local", "seconds": 2.5}""").status)
        assertEquals(400, call("POST", "/url", """{"url": "http://cam.local", "seconds": "30"}""").status)
        assertEquals(400, call("POST", "/url", """{"url": "http://cam.local", "seconds": 30, "home": true}""").status)
        assertEquals(1, kiosk.calls.size)
    }

    @Test
    fun brightness() {
        assertEquals(200, call("POST", "/brightness", """{"level": 40}""").status)
        assertEquals(200, call("POST", "/brightness", """{"level": 100.0}""").status)
        assertEquals(200, call("POST", "/brightness", """{"level": "auto"}""").status)
        assertEquals(400, call("POST", "/brightness", """{"level": 0}""").status)
        assertEquals(400, call("POST", "/brightness", """{"level": 101}""").status)
        assertEquals(400, call("POST", "/brightness", """{"level": "50"}""").status)
        assertEquals(400, call("POST", "/brightness", """{}""").status)
        assertEquals(listOf("brightness 40", "brightness 100", "brightness null"), kiosk.calls)
    }

    @Test
    fun volume() {
        assertEquals(200, call("POST", "/volume", """{"level": 0}""").status)
        assertEquals(200, call("POST", "/volume", """{"level": 100}""").status)
        assertEquals(400, call("POST", "/volume", """{"level": -1}""").status)
        assertEquals(400, call("POST", "/volume", """{"level": "loud"}""").status)
        assertEquals(listOf("volume 0", "volume 100"), kiosk.calls)
    }

    @Test
    fun speak() {
        assertEquals(200, call("POST", "/speak", """{"text": " Someone is at the door "}""").status)
        assertEquals(200, call("POST", "/speak", """{"text": "Hallo", "language": "de-DE"}""").status)
        assertEquals(400, call("POST", "/speak", """{"text": "  "}""").status)
        assertEquals(400, call("POST", "/speak", """{"text": "${"a".repeat(1001)}"}""").status)
        assertEquals(400, call("POST", "/speak", """{"text": "Hi", "language": "not a tag!"}""").status)
        assertEquals(listOf("speak Someone is at the door (null)", "speak Hallo (de-DE)"), kiosk.calls)

        kiosk.speakProblem = "No engine"
        val refused = call("POST", "/speak", """{"text": "Hi"}""")
        assertEquals(503, refused.status)
        assertEquals("No engine", refused.body.getString("error"))
    }

    @Test
    fun sound() {
        assertEquals(200, call("POST", "/sound", """{"url": "http://ha.local:8123/local/chime.mp3"}""").status)
        assertEquals(200, call("POST", "/sound", """{"stop": true}""").status)
        assertEquals(400, call("POST", "/sound", """{"url": "chime.mp3"}""").status)
        assertEquals(400, call("POST", "/sound", """{"url": "file:///sdcard/chime.mp3"}""").status)
        assertEquals(400, call("POST", "/sound", """{}""").status)
        assertEquals(listOf("sound http://ha.local:8123/local/chime.mp3", "sound null"), kiosk.calls)
    }

    @Test
    fun message() {
        assertEquals(200, call("POST", "/message", """{"text": "Dinner's ready"}""").status)
        assertEquals(200, call("POST", "/message", """{"text": "Stays", "seconds": 0}""").status)
        assertEquals(400, call("POST", "/message", """{"text": ""}""").status)
        assertEquals(400, call("POST", "/message", """{"text": "Hi", "seconds": -1}""").status)
        assertEquals(400, call("POST", "/message", """{"text": "${"a".repeat(501)}"}""").status)
        assertEquals(listOf("message Dinner's ready for 10", "message Stays for 0"), kiosk.calls)
    }

    @Test
    fun screenshot() {
        val response = call("GET", "/screenshot")
        assertEquals(200, response.status)
        assertEquals("image/jpeg", response.contentType)
        val written = java.io.ByteArrayOutputStream().also { response.write(it) }.toByteArray()
        assertTrue(written.toString(Charsets.ISO_8859_1).startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue(written.takeLast(3) == listOf<Byte>(1, 2, 3))

        kiosk.screenshot = null
        assertEquals(403, call("GET", "/screenshot").status)
        assertEquals(405, call("POST", "/screenshot").status)
    }

    @Test
    fun wrongMethodOrPath() {
        assertEquals(405, call("POST", "/status").status)
        assertEquals(405, call("GET", "/reload").status)
        assertEquals(405, call("GET", "/speak").status)
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
