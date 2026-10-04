package com.byyako.dkiosk.remote

import com.byyako.dkiosk.web.normalizeHomeUrl
import org.json.JSONException
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** What the API can ask the kiosk to do. Implementations run each call on the main thread. */
interface KioskControl {
    fun status(): JSONObject

    fun reload()

    fun goHome()

    /**
     * Opens [url], also making it the home page if [makeHome]. With [seconds], goes back to the
     * previous page after that long. Returns an error message, or null on success.
     */
    fun open(url: String, makeHome: Boolean, seconds: Int? = null): String?

    fun setScreen(on: Boolean)

    /** [percent] from 1 to 100, or null for Android's own brightness. */
    fun setBrightness(percent: Int?)

    fun setVolume(percent: Int)

    /** Returns an error message, or null once the text is queued. */
    fun speak(text: String, language: String?): String?

    /** Plays [url], replacing any sound already playing; null stops it. */
    fun playSound(url: String?)

    /** Shows [text] over the page for [seconds], or until tapped when 0. */
    fun showMessage(text: String, seconds: Int)

    /** A JPEG of the screen, or null if screenshots are turned off. Throws if capturing fails. */
    fun screenshot(): ByteArray?
}

/**
 * Routes API requests. Every request needs `Authorization: Bearer <token>`.
 *
 *     GET  /status
 *     GET  /screenshot
 *     POST /reload
 *     POST /home
 *     POST /url         {"url": "https://...", "home": false, "seconds": 30}
 *     POST /screen      {"state": "on" | "off"}
 *     POST /brightness  {"level": 1-100 | "auto"}
 *     POST /volume      {"level": 0-100}
 *     POST /speak       {"text": "...", "language": "en-US"}
 *     POST /sound       {"url": "https://..."} or {"stop": true}
 *     POST /message     {"text": "...", "seconds": 10}
 */
class KioskApi(private val token: String, private val control: KioskControl) {

    init {
        require(token.isNotBlank()) { "The API needs a token" }
    }

    fun handle(request: HttpRequest): HttpResponse {
        if (!isAuthorized(request)) return HttpResponse.error(401, "Missing or wrong token")

        return when (request.method to request.path) {
            "GET" to "/status" -> HttpResponse.ok(control.status())
            "GET" to "/screenshot" -> screenshot()
            "POST" to "/reload" -> HttpResponse.ok().also { control.reload() }
            "POST" to "/home" -> HttpResponse.ok().also { control.goHome() }
            "POST" to "/url" -> withJson(request, ::openUrl)
            "POST" to "/screen" -> withJson(request, ::setScreen)
            "POST" to "/brightness" -> withJson(request, ::setBrightness)
            "POST" to "/volume" -> withJson(request, ::setVolume)
            "POST" to "/speak" -> withJson(request, ::speak)
            "POST" to "/sound" -> withJson(request, ::playSound)
            "POST" to "/message" -> withJson(request, ::showMessage)
            else -> when (request.path) {
                in GET_PATHS -> HttpResponse.error(405, "Use GET")
                in POST_PATHS -> HttpResponse.error(405, "Use POST")
                else -> HttpResponse.error(404, "No such endpoint")
            }
        }
    }

    private fun withJson(request: HttpRequest, handler: (JSONObject) -> HttpResponse): HttpResponse {
        val json = try {
            JSONObject(request.body)
        } catch (_: JSONException) {
            return HttpResponse.error(400, "Body must be a JSON object")
        }
        return handler(json)
    }

    private fun openUrl(json: JSONObject): HttpResponse {
        val url = normalizeHomeUrl(json.optString("url"))
            ?: return HttpResponse.error(400, "\"url\" must be an http(s) address")
        val makeHome = json.optBoolean("home", false)
        val seconds = if (json.has("seconds")) {
            json.wholeNumber("seconds")?.takeIf { it in 1..MAX_SECONDS }
                ?: return HttpResponse.error(400, "\"seconds\" must be a whole number from 1 to $MAX_SECONDS")
        } else null
        if (makeHome && seconds != null) return HttpResponse.error(400, "A temporary page can't be the home page")
        val problem = control.open(url, makeHome, seconds)
        return if (problem == null) HttpResponse.ok() else HttpResponse.error(403, problem)
    }

    private fun setScreen(json: JSONObject): HttpResponse {
        when (json.optString("state")) {
            "on" -> control.setScreen(true)
            "off" -> control.setScreen(false)
            else -> return HttpResponse.error(400, "\"state\" must be \"on\" or \"off\"")
        }
        return HttpResponse.ok()
    }

    private fun setBrightness(json: JSONObject): HttpResponse {
        if (json.opt("level") == "auto") {
            control.setBrightness(null)
            return HttpResponse.ok()
        }
        val level = json.wholeNumber("level")?.takeIf { it in 1..100 }
            ?: return HttpResponse.error(400, "\"level\" must be a whole number from 1 to 100, or \"auto\"")
        control.setBrightness(level)
        return HttpResponse.ok()
    }

    private fun setVolume(json: JSONObject): HttpResponse {
        val level = json.wholeNumber("level")?.takeIf { it in 0..100 }
            ?: return HttpResponse.error(400, "\"level\" must be a whole number from 0 to 100")
        control.setVolume(level)
        return HttpResponse.ok()
    }

    private fun speak(json: JSONObject): HttpResponse {
        val text = json.optString("text").trim()
        if (text.isEmpty() || text.length > MAX_SPEECH) {
            return HttpResponse.error(400, "\"text\" must be 1 to $MAX_SPEECH characters")
        }
        val language = json.optString("language").trim().ifEmpty { null }
        if (language != null && !LANGUAGE_TAG.matches(language)) {
            return HttpResponse.error(400, "\"language\" must be a tag like \"en-US\"")
        }
        val problem = control.speak(text, language)
        return if (problem == null) HttpResponse.ok() else HttpResponse.error(503, problem)
    }

    private fun playSound(json: JSONObject): HttpResponse {
        if (json.optBoolean("stop", false)) {
            control.playSound(null)
            return HttpResponse.ok()
        }
        val url = json.optString("url").trim()
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true) ||
            normalizeHomeUrl(url) == null
        ) {
            return HttpResponse.error(400, "\"url\" must be an http(s) address, or send {\"stop\": true}")
        }
        control.playSound(url)
        return HttpResponse.ok()
    }

    private fun showMessage(json: JSONObject): HttpResponse {
        val text = json.optString("text").trim()
        if (text.isEmpty() || text.length > MAX_MESSAGE) {
            return HttpResponse.error(400, "\"text\" must be 1 to $MAX_MESSAGE characters")
        }
        val seconds = if (json.has("seconds")) {
            json.wholeNumber("seconds")?.takeIf { it in 0..MAX_SECONDS }
                ?: return HttpResponse.error(400, "\"seconds\" must be a whole number from 0 to $MAX_SECONDS")
        } else DEFAULT_MESSAGE_SECONDS
        control.showMessage(text, seconds)
        return HttpResponse.ok()
    }

    private fun screenshot(): HttpResponse {
        val jpeg = control.screenshot()
            ?: return HttpResponse.error(403, "Screenshots are turned off in the kiosk's settings")
        return HttpResponse.jpeg(jpeg)
    }

    /** The value at [key] if it's a whole number (5 or 5.0, not "5" or 5.5). */
    private fun JSONObject.wholeNumber(key: String): Int? {
        val number = opt(key) as? Number ?: return null
        val value = number.toDouble()
        return if (value == Math.floor(value) && value in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()) {
            value.toInt()
        } else null
    }

    private fun isAuthorized(request: HttpRequest): Boolean {
        val authorization = request.header("Authorization") ?: return false
        val parts = authorization.split(' ', limit = 2)
        if (parts.size != 2 || !parts[0].equals("Bearer", ignoreCase = true)) return false
        val given = parts[1].trim()
        // Constant-time, so response timing doesn't leak how much of a guess was right.
        return MessageDigest.isEqual(given.toByteArray(), token.toByteArray())
    }

    companion object {
        const val MAX_SECONDS = 3600
        const val MAX_SPEECH = 1000
        const val MAX_MESSAGE = 500
        const val DEFAULT_MESSAGE_SECONDS = 10

        private val GET_PATHS = setOf("/status", "/screenshot")
        private val POST_PATHS =
            setOf("/reload", "/home", "/url", "/screen", "/brightness", "/volume", "/speak", "/sound", "/message")
        private val LANGUAGE_TAG = Regex("[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*")

        fun newToken(): String {
            val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }
    }
}
