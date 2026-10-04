package com.byyako.dkiosk.remote

import com.byyako.dkiosk.web.normalizeHomeUrl
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Home Assistant's MQTT discovery for one kiosk: the entities it announces, the state it reports
 * and the commands it accepts. Commands go through the same [KioskControl] as the HTTP API.
 * Unlike the API, MQTT can't change the home page, so broker access is less powerful than the token.
 */
class HomeAssistant(
    private val deviceId: String,
    private val deviceName: String,
    private val model: String,
    private val version: String,
    private val discoveryPrefix: String = "homeassistant",
) {
    val base = "dkiosk/$deviceId"
    val availabilityTopic = "$base/availability"
    val stateTopic = "$base/state"
    val screenshotTopic = "$base/screenshot"
    val commandFilter = "$base/cmd/+"
    private val commandPrefix = "$base/cmd/"

    sealed interface Result {
        data object Done : Result

        class Failed(val reason: String) : Result

        class Screenshot(val jpeg: ByteArray) : Result
    }

    /** Discovery messages to publish (retained). An empty payload removes an entity. */
    fun discovery(screenshots: Boolean, motion: Boolean = false): List<Pair<String, String>> {
        val entities = mutableListOf<Pair<String, String>>()
        fun add(component: String, key: String, config: JSONObject?) {
            val topic = "$discoveryPrefix/$component/dkiosk_$deviceId/$key/config"
            entities += topic to (config?.let { withCommon(it, key) }?.toString() ?: "")
        }

        add(
            "light", "screen",
            JSONObject()
                .put("name", "Screen")
                .put("icon", "mdi:tablet-dashboard")
                .put("command_topic", command("screen"))
                .put("state_topic", stateTopic)
                .put("state_value_template", "{{ 'ON' if value_json.screen == 'on' else 'OFF' }}")
                .put("brightness_command_topic", command("brightness"))
                .put("brightness_state_topic", stateTopic)
                .put("brightness_value_template", "{{ value_json.brightness if value_json.brightness is number else 100 }}")
                .put("brightness_scale", 100)
                .put("on_command_type", "first"),
        )
        add("button", "auto_brightness", button("Automatic brightness", "brightness", "mdi:brightness-auto", "auto"))
        add("button", "reload", button("Reload", "reload", "mdi:refresh"))
        add("button", "home", button("Go home", "home", "mdi:home"))
        add(
            "number", "volume",
            JSONObject()
                .put("name", "Volume")
                .put("icon", "mdi:volume-high")
                .put("command_topic", command("volume"))
                .put("state_topic", stateTopic)
                .put("value_template", "{{ value_json.volume }}")
                .put("min", 0).put("max", 100).put("step", 1)
                .put("unit_of_measurement", "%")
                .put("mode", "slider"),
        )
        add(
            "text", "url",
            JSONObject()
                .put("name", "Page")
                .put("icon", "mdi:web")
                .put("command_topic", command("url"))
                .put("state_topic", stateTopic)
                .put("value_template", "{{ (value_json.url or '')[:255] }}")
                .put("max", 255),
        )
        add(
            "text", "sound",
            JSONObject()
                .put("name", "Play sound")
                .put("icon", "mdi:music-note")
                .put("command_topic", command("sound"))
                .put("max", 255),
        )
        add("notify", "message", JSONObject().put("name", "Message").put("command_topic", command("message")))
        add(
            "notify", "speak",
            JSONObject().put("name", "Speak").put("icon", "mdi:account-voice").put("command_topic", command("speak")),
        )
        add(
            "sensor", "battery",
            sensor("Battery", "{{ value_json.battery }}")
                .put("device_class", "battery").put("unit_of_measurement", "%").put("state_class", "measurement"),
        )
        add(
            "binary_sensor", "charging",
            sensor("Charging", "{{ 'ON' if value_json.charging else 'OFF' }}").put("device_class", "battery_charging"),
        )
        add("sensor", "current_page", sensor("Current page", "{{ (value_json.url or '')[:255] }}").put("icon", "mdi:web"))
        add(
            "sensor", "load_error",
            sensor("Load error", "{{ value_json.error or 'None' }}").put("icon", "mdi:alert-circle-outline"),
        )
        add(
            "sensor", "last_touch",
            sensor("Last touch", "{{ value_json.lastTouch }}").put("device_class", "timestamp").put("icon", "mdi:gesture-tap"),
        )
        add(
            "binary_sensor", "motion",
            if (motion) {
                sensor("Motion", "{{ 'ON' if value_json.motion else 'OFF' }}").put("device_class", "motion")
            } else null,
        )
        add(
            "binary_sensor", "screensaver",
            sensor("Screensaver", "{{ 'ON' if value_json.screensaver else 'OFF' }}").put("icon", "mdi:clock-outline"),
        )
        // Without screenshots allowed, the entities are removed rather than left broken.
        add("button", "screenshot", if (screenshots) button("Take screenshot", "screenshot", "mdi:camera") else null)
        add(
            "image", "screenshot_image",
            if (screenshots) {
                JSONObject().put("name", "Screenshot").put("image_topic", screenshotTopic).put("content_type", "image/jpeg")
            } else null,
        )
        return entities
    }

    /** Runs a command received on [topic]. Topics outside this kiosk's command space are ignored. */
    fun handle(topic: String, payload: ByteArray, control: KioskControl): Result? {
        if (!topic.startsWith(commandPrefix)) return null
        val text = payload.toString(Charsets.UTF_8).trim()
        return when (topic.removePrefix(commandPrefix)) {
            "screen" -> when (text.uppercase()) {
                "ON" -> Result.Done.also { control.setScreen(true) }
                "OFF" -> Result.Done.also { control.setScreen(false) }
                else -> Result.Failed("Screen takes ON or OFF")
            }
            "brightness" -> brightness(text, control)
            "volume" -> text.toDoubleOrNull()?.takeIf { it in 0.0..100.0 }
                ?.let { Result.Done.also { _ -> control.setVolume(Math.round(it).toInt()) } }
                ?: Result.Failed("Volume takes 0 to 100")
            "reload" -> Result.Done.also { control.reload() }
            "home" -> Result.Done.also { control.goHome() }
            "url" -> openUrl(text, control)
            "sound" -> sound(text, control)
            "message" -> message(text, control)
            "speak" -> speak(text, control)
            "screenshot" -> control.screenshot()?.let { Result.Screenshot(it) }
                ?: Result.Failed("Screenshots are turned off in the kiosk's settings")
            else -> Result.Failed("Unknown command")
        }
    }

    private fun brightness(text: String, control: KioskControl): Result {
        if (text.equals("auto", ignoreCase = true)) {
            control.setBrightness(null)
            return Result.Done
        }
        val level = text.toDoubleOrNull()?.let { Math.round(it).toInt() }?.takeIf { it in 0..100 }
            ?: return Result.Failed("Brightness takes 1 to 100 or auto")
        // Home Assistant can send 0 when a slider is dragged all the way down; the screen stays usable.
        control.setBrightness(level.coerceAtLeast(1))
        return Result.Done
    }

    /** A plain address, or {"url": ..., "seconds": ...} for a temporary page. */
    private fun openUrl(text: String, control: KioskControl): Result {
        val json = jsonOrNull(text)
        val url = normalizeHomeUrl(json?.optString("url") ?: text) ?: return Result.Failed("Not an http(s) address")
        val seconds = json?.optInt("seconds", 0)?.takeIf { it > 0 }
        if (seconds != null && seconds > KioskApi.MAX_SECONDS) return Result.Failed("seconds is at most ${KioskApi.MAX_SECONDS}")
        return control.open(url, makeHome = false, seconds = seconds)?.let { Result.Failed(it) } ?: Result.Done
    }

    private fun sound(text: String, control: KioskControl): Result {
        if (text.isEmpty() || text.equals("stop", ignoreCase = true)) {
            control.playSound(null)
            return Result.Done
        }
        val explicit = text.startsWith("http://", ignoreCase = true) || text.startsWith("https://", ignoreCase = true)
        if (!explicit || normalizeHomeUrl(text) == null) return Result.Failed("Play sound takes an http(s) address or stop")
        control.playSound(text)
        return Result.Done
    }

    /** Plain text, or {"text"|"message": ..., "seconds": ...}. */
    private fun message(text: String, control: KioskControl): Result {
        val json = jsonOrNull(text)
        val message = (json?.optString("text")?.ifEmpty { null } ?: json?.optString("message") ?: text).trim()
        if (message.isEmpty() || message.length > KioskApi.MAX_MESSAGE) {
            return Result.Failed("Messages are 1 to ${KioskApi.MAX_MESSAGE} characters")
        }
        val seconds = json?.optInt("seconds", KioskApi.DEFAULT_MESSAGE_SECONDS) ?: KioskApi.DEFAULT_MESSAGE_SECONDS
        if (seconds !in 0..KioskApi.MAX_SECONDS) return Result.Failed("seconds is 0 to ${KioskApi.MAX_SECONDS}")
        control.showMessage(message, seconds)
        return Result.Done
    }

    /** Plain text, or {"text": ..., "language": ...}. */
    private fun speak(text: String, control: KioskControl): Result {
        val json = jsonOrNull(text)
        val speech = (json?.optString("text") ?: text).trim()
        if (speech.isEmpty() || speech.length > KioskApi.MAX_SPEECH) {
            return Result.Failed("Speech is 1 to ${KioskApi.MAX_SPEECH} characters")
        }
        val language = json?.optString("language")?.trim()?.ifEmpty { null }
        return control.speak(speech, language)?.let { Result.Failed(it) } ?: Result.Done
    }

    private fun jsonOrNull(text: String): JSONObject? {
        if (!text.startsWith("{")) return null
        return try {
            JSONObject(text)
        } catch (_: JSONException) {
            null
        }
    }

    private fun command(name: String) = commandPrefix + name

    private fun button(name: String, command: String, icon: String, payload: String = "PRESS") = JSONObject()
        .put("name", name)
        .put("icon", icon)
        .put("command_topic", command(command))
        .put("payload_press", payload)

    private fun sensor(name: String, template: String) = JSONObject()
        .put("name", name)
        .put("state_topic", stateTopic)
        .put("value_template", template)

    private fun withCommon(config: JSONObject, key: String): JSONObject = config
        .put("unique_id", "dkiosk_${deviceId}_$key")
        .put("availability_topic", availabilityTopic)
        .put(
            "device",
            JSONObject()
                .put("identifiers", JSONArray().put("dkiosk_$deviceId"))
                .put("name", deviceName)
                .put("manufacturer", "dKiosk")
                .put("model", model)
                .put("sw_version", version),
        )
        .put("origin", JSONObject().put("name", "dKiosk").put("sw_version", version).put("support_url", SUPPORT_URL))

    companion object {
        const val ONLINE = "online"
        const val OFFLINE = "offline"
        private const val SUPPORT_URL = "https://github.com/byyako/dkiosk"
    }
}
