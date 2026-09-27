package com.byyako.dkiosk.remote

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class HttpRequestTest {

    private fun read(raw: String) = HttpRequest.read(raw.byteInputStream())

    @Test
    fun readsAGet() {
        val request = read("GET /status HTTP/1.1\r\nHost: kiosk\r\nAuthorization: Bearer abc\r\n\r\n")
        assertEquals("GET", request.method)
        assertEquals("/status", request.path)
        assertEquals("Bearer abc", request.header("authorization"))
        assertEquals("kiosk", request.header("HOST"))
        assertNull(request.header("X-Missing"))
        assertEquals("", request.body)
    }

    @Test
    fun readsAPostBody() {
        val body = """{"url":"https://example.com/ä"}"""
        val bytes = body.toByteArray().size
        val request = read("post /url HTTP/1.1\r\nContent-Length: $bytes\r\n\r\n$body")
        assertEquals("POST", request.method)
        assertEquals(body, request.body)
    }

    @Test
    fun ignoresQueryAndTrailingSlash() {
        assertEquals("/status", read("GET /status/?x=1 HTTP/1.1\r\n\r\n").path)
        assertEquals("/", read("GET / HTTP/1.1\r\n\r\n").path)
    }

    @Test(expected = BadRequestException::class)
    fun rejectsAMalformedRequestLine() {
        read("HELLO\r\n\r\n")
    }

    @Test(expected = BadRequestException::class)
    fun rejectsAHeaderWithoutAColon() {
        read("GET / HTTP/1.1\r\nnonsense\r\n\r\n")
    }

    @Test(expected = BadRequestException::class)
    fun rejectsAnOversizedBody() {
        read("POST /url HTTP/1.1\r\nContent-Length: 999999\r\n\r\n")
    }

    @Test(expected = BadRequestException::class)
    fun rejectsATruncatedBody() {
        read("POST /url HTTP/1.1\r\nContent-Length: 10\r\n\r\nabc")
    }

    @Test(expected = BadRequestException::class)
    fun rejectsHugeHeaders() {
        read("GET / HTTP/1.1\r\nX-Big: ${"a".repeat(10_000)}\r\n\r\n")
    }

    @Test(expected = BadRequestException::class)
    fun rejectsARequestThatNeverEnds() {
        read("GET / HTTP/1.1\r\nHost: x\r\n")
    }

    @Test(expected = BadRequestException::class)
    fun givesUpOnASlowClient() {
        // Every read of the clock moves time on by a second, so the 10 s budget runs out mid-headers.
        var now = 0L
        HttpRequest.read("GET / HTTP/1.1\r\nHost: kiosk.lan\r\n\r\n".byteInputStream()) { now += 1_000; now }
    }

    @Test
    fun writesAJsonResponse() {
        val out = ByteArrayOutputStream()
        HttpResponse(200, JSONObject().put("ok", true)).write(out)
        val text = out.toString(Charsets.UTF_8.name())
        assertTrue(text.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue("Content-Length: 11\r\n" in text)
        assertTrue(text.endsWith("\r\n\r\n{\"ok\":true}"))
    }
}
