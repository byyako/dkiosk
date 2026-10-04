package com.byyako.dkiosk.remote

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeAssistantTest {

    private val ha = HomeAssistant("abc123", "Hall kiosk", "Pixel", "1.2.0")
    private val kiosk = FakeKiosk()

    private fun send(command: String, payload: String) = ha.handle("dkiosk/abc123/cmd/$command", payload.toByteArray(), kiosk)

    private fun failed(result: HomeAssistant.Result?) = result is HomeAssistant.Result.Failed

    @Test
    fun discoveryDescribesOneDevice() {
        val configs = ha.discovery(screenshots = true).toMap()
        val screen = JSONObject(configs.getValue("homeassistant/light/dkiosk_abc123/screen/config"))
        assertEquals("Screen", screen.getString("name"))
        assertEquals("dkiosk_abc123_screen", screen.getString("unique_id"))
        assertEquals("dkiosk/abc123/cmd/screen", screen.getString("command_topic"))
        assertEquals("dkiosk/abc123/availability", screen.getString("availability_topic"))
        assertEquals("Hall kiosk", screen.getJSONObject("device").getString("name"))
        assertEquals("dkiosk_abc123", screen.getJSONObject("device").getJSONArray("identifiers").getString(0))

        // Every entity belongs to the same device and has its own ID.
        val ids = configs.values.filter { it.isNotEmpty() }.map { JSONObject(it).getString("unique_id") }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(configs.containsKey("homeassistant/image/dkiosk_abc123/screenshot_image/config"))
    }

    @Test
    fun screenshotEntitiesAreRemovedWhenNotAllowed() {
        val configs = ha.discovery(screenshots = false).toMap()
        assertEquals("", configs.getValue("homeassistant/button/dkiosk_abc123/screenshot/config"))
        assertEquals("", configs.getValue("homeassistant/image/dkiosk_abc123/screenshot_image/config"))
        assertTrue(configs.getValue("homeassistant/button/dkiosk_abc123/reload/config").isNotEmpty())
    }

    @Test
    fun motionSensorOnlyWithAMotionSource() {
        val without = ha.discovery(screenshots = false, motion = false).toMap()
        assertEquals("", without.getValue("homeassistant/binary_sensor/dkiosk_abc123/motion/config"))
        val with = ha.discovery(screenshots = false, motion = true).toMap()
        val motion = JSONObject(with.getValue("homeassistant/binary_sensor/dkiosk_abc123/motion/config"))
        assertEquals("motion", motion.getString("device_class"))
    }

    @Test
    fun screenAndBrightness() {
        send("screen", "OFF")
        send("screen", "on")
        send("brightness", "40")
        send("brightness", "0")
        send("brightness", "auto")
        assertTrue(failed(send("screen", "dim")))
        assertTrue(failed(send("brightness", "250")))
        assertEquals(
            listOf("screen false", "screen true", "brightness 40", "brightness 1", "brightness null"),
            kiosk.calls,
        )
    }

    @Test
    fun volumeAcceptsHomeAssistantNumbers() {
        send("volume", "35.0")
        assertTrue(failed(send("volume", "101")))
        assertTrue(failed(send("volume", "loud")))
        assertEquals(listOf("volume 35"), kiosk.calls)
    }

    @Test
    fun urlPlainOrTemporary() {
        send("url", "http://cam.local/door")
        send("url", """{"url": "http://cam.local/door", "seconds": 30}""")
        assertTrue(failed(send("url", "ftp://x")))
        assertTrue(failed(send("url", """{"url": "http://cam.local", "seconds": 5000}""")))
        kiosk.openProblem = "blocked"
        assertTrue(failed(send("url", "http://elsewhere")))
        assertEquals(
            listOf(
                "open http://cam.local/door home=false",
                "open http://cam.local/door home=false for 30",
                "open http://elsewhere home=false",
            ),
            kiosk.calls,
        )
    }

    @Test
    fun messagesSpeechAndSound() {
        send("message", "Dinner's ready")
        send("message", """{"text": "Doorbell", "seconds": 0}""")
        send("speak", "Hello")
        send("speak", """{"text": "Hallo", "language": "de-DE"}""")
        send("sound", "http://ha.local/chime.mp3")
        send("sound", "stop")
        assertTrue(failed(send("message", "")))
        assertTrue(failed(send("sound", "chime.mp3")))
        assertEquals(
            listOf(
                "message Dinner's ready for 10",
                "message Doorbell for 0",
                "speak Hello (null)",
                "speak Hallo (de-DE)",
                "sound http://ha.local/chime.mp3",
                "sound null",
            ),
            kiosk.calls,
        )
    }

    @Test
    fun screenshotReturnsTheImage() {
        val result = send("screenshot", "PRESS")
        assertArrayEquals(byteArrayOf(1, 2, 3), (result as HomeAssistant.Result.Screenshot).jpeg)
        kiosk.screenshot = null
        assertTrue(failed(send("screenshot", "PRESS")))
    }

    @Test
    fun ignoresOtherTopics() {
        assertNull(ha.handle("dkiosk/other/cmd/reload", byteArrayOf(), kiosk))
        assertNull(ha.handle("homeassistant/status", "online".toByteArray(), kiosk))
        assertTrue(failed(send("format_disk", "")))
        assertTrue(kiosk.calls.isEmpty())
    }
}
