package com.byyako.dkiosk.remote

import org.json.JSONObject

/** Records what the API and MQTT commands ask the kiosk to do. */
class FakeKiosk : KioskControl {
    val calls = mutableListOf<String>()
    var openProblem: String? = null
    var speakProblem: String? = null
    var screenshot: ByteArray? = byteArrayOf(1, 2, 3)

    override fun status(): JSONObject = JSONObject().put("screen", "on")

    override fun reload() {
        calls += "reload"
    }

    override fun goHome() {
        calls += "home"
    }

    override fun open(url: String, makeHome: Boolean, seconds: Int?): String? {
        calls += "open $url home=$makeHome" + (seconds?.let { " for $it" } ?: "")
        return openProblem
    }

    override fun setScreen(on: Boolean) {
        calls += "screen $on"
    }

    override fun setBrightness(percent: Int?) {
        calls += "brightness $percent"
    }

    override fun setVolume(percent: Int) {
        calls += "volume $percent"
    }

    override fun speak(text: String, language: String?): String? {
        calls += "speak $text ($language)"
        return speakProblem
    }

    override fun playSound(url: String?) {
        calls += "sound $url"
    }

    override fun showMessage(text: String, seconds: Int) {
        calls += "message $text for $seconds"
    }

    override fun screenshot(): ByteArray? = screenshot
}
