package com.byyako.dkiosk.remote

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Just enough HTTP/1.1 for a handful of JSON commands: one request per connection, no chunking. */
class HttpRequest(
    val method: String,
    val path: String,
    private val headers: Map<String, String>,
    val body: String,
) {
    fun header(name: String): String? = headers[name.lowercase()]

    companion object {
        private const val MAX_HEAD_BYTES = 8 * 1024
        private const val MAX_BODY_BYTES = 16 * 1024
        private const val MAX_READ_MS = 10_000L

        /**
         * Reads one request, throwing [BadRequestException] for anything malformed, oversized or too slow
         * (a client trickling in a byte at a time would otherwise tie up a worker for hours).
         */
        fun read(input: InputStream, clock: () -> Long = System::currentTimeMillis): HttpRequest {
            val deadline = clock() + MAX_READ_MS
            val checkTime = {
                if (clock() > deadline) throw BadRequestException("Request took too long")
            }

            val lines = readHead(input, checkTime).split("\r\n")
            val requestLine = lines.first().split(" ")
            if (requestLine.size != 3 || !requestLine[2].startsWith("HTTP/")) {
                throw BadRequestException("Malformed request line")
            }

            val headers = lines.drop(1).filter { it.isNotEmpty() }.associate { line ->
                val colon = line.indexOf(':')
                if (colon <= 0) throw BadRequestException("Malformed header")
                line.substring(0, colon).trim().lowercase() to line.substring(colon + 1).trim()
            }

            val length = headers["content-length"]?.let {
                it.toIntOrNull() ?: throw BadRequestException("Bad Content-Length")
            } ?: 0
            if (length !in 0..MAX_BODY_BYTES) throw BadRequestException("Body too large")
            val body = ByteArray(length)
            var read = 0
            while (read < length) {
                checkTime()
                val count = input.read(body, read, length - read)
                if (count < 0) throw BadRequestException("Body ended early")
                read += count
            }

            val path = requestLine[1].substringBefore('?').trimEnd('/').ifEmpty { "/" }
            return HttpRequest(requestLine[0].uppercase(), path, headers, body.toString(Charsets.UTF_8))
        }

        /** Everything up to the blank line that ends the headers. */
        private fun readHead(input: InputStream, checkTime: () -> Unit): String {
            val head = ByteArrayOutputStream()
            var matched = 0 // how much of "\r\n\r\n" has been seen
            while (matched < 4) {
                checkTime()
                val byte = input.read()
                if (byte < 0) throw BadRequestException("Connection closed mid-request")
                head.write(byte)
                if (head.size() > MAX_HEAD_BYTES) throw BadRequestException("Headers too large")
                matched = when {
                    byte == '\r'.code -> if (matched == 2) 3 else 1
                    byte == '\n'.code && (matched == 1 || matched == 3) -> matched + 1
                    else -> 0
                }
            }
            return head.toString(Charsets.ISO_8859_1.name()).dropLast(4)
        }
    }
}

/** A JSON response, or with [payload] a binary one such as a screenshot. */
class HttpResponse(
    val status: Int,
    val body: JSONObject,
    private val payload: ByteArray? = null,
    val contentType: String = JSON,
) {

    fun write(output: OutputStream) {
        val bytes = payload ?: body.toString().toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $status ${reason(status)}\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n"
        output.write(head.toByteArray(Charsets.ISO_8859_1))
        output.write(bytes)
        output.flush()
    }

    companion object {
        private const val JSON = "application/json; charset=utf-8"

        fun ok(body: JSONObject = JSONObject().put("ok", true)) = HttpResponse(200, body)

        fun error(status: Int, message: String) = HttpResponse(status, JSONObject().put("error", message))

        fun jpeg(bytes: ByteArray) = HttpResponse(200, JSONObject(), bytes, "image/jpeg")

        private fun reason(status: Int) = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            503 -> "Service Unavailable"
            else -> "Internal Server Error"
        }
    }
}

class BadRequestException(message: String) : IOException(message)
