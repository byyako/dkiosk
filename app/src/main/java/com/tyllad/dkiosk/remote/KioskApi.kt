package com.tyllad.dkiosk.remote

import com.tyllad.dkiosk.web.normalizeHomeUrl
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import org.json.JSONException
import org.json.JSONObject

/** What the API can ask the kiosk to do. Implementations run each call on the main thread. */
interface KioskControl {
    fun status(): JSONObject

    fun reload()

    fun goHome()

    /** Opens [url], also making it the home page if [makeHome]. Returns an error message, or null on success. */
    fun open(url: String, makeHome: Boolean): String?

    fun setScreen(on: Boolean)
}

/**
 * Routes API requests. Every request needs `Authorization: Bearer <token>`.
 *
 *     GET  /status
 *     POST /reload
 *     POST /home
 *     POST /url     {"url": "https://...", "home": false}
 *     POST /screen  {"state": "on" | "off"}
 */
class KioskApi(private val token: String, private val control: KioskControl) {

    init {
        require(token.isNotBlank()) { "The API needs a token" }
    }

    fun handle(request: HttpRequest): HttpResponse {
        if (!isAuthorized(request)) return HttpResponse.error(401, "Missing or wrong token")

        return when (request.method to request.path) {
            "GET" to "/status" -> HttpResponse.ok(control.status())
            "POST" to "/reload" -> HttpResponse.ok().also { control.reload() }
            "POST" to "/home" -> HttpResponse.ok().also { control.goHome() }
            "POST" to "/url" -> openUrl(request)
            "POST" to "/screen" -> setScreen(request)
            else -> when (request.path) {
                "/status" -> HttpResponse.error(405, "Use GET")
                in POST_PATHS -> HttpResponse.error(405, "Use POST")
                else -> HttpResponse.error(404, "No such endpoint")
            }
        }
    }

    private fun openUrl(request: HttpRequest): HttpResponse {
        val json = parse(request) ?: return HttpResponse.error(400, "Body must be a JSON object")
        val url = normalizeHomeUrl(json.optString("url")) ?: return HttpResponse.error(400, "\"url\" must be an http(s) address")
        val problem = control.open(url, json.optBoolean("home", false))
        return if (problem == null) HttpResponse.ok() else HttpResponse.error(403, problem)
    }

    private fun setScreen(request: HttpRequest): HttpResponse {
        val json = parse(request) ?: return HttpResponse.error(400, "Body must be a JSON object")
        when (json.optString("state")) {
            "on" -> control.setScreen(true)
            "off" -> control.setScreen(false)
            else -> return HttpResponse.error(400, "\"state\" must be \"on\" or \"off\"")
        }
        return HttpResponse.ok()
    }

    private fun parse(request: HttpRequest): JSONObject? = try {
        JSONObject(request.body)
    } catch (_: JSONException) {
        null
    }

    private fun isAuthorized(request: HttpRequest): Boolean {
        val given = request.header("Authorization")?.removePrefix("Bearer ")?.trim() ?: return false
        // Constant-time, so response timing doesn't leak how much of a guess was right.
        return MessageDigest.isEqual(given.toByteArray(), token.toByteArray())
    }

    companion object {
        private val POST_PATHS = setOf("/reload", "/home", "/url", "/screen")

        fun newToken(): String {
            val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }
    }
}
