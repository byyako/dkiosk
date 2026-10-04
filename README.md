# dKiosk Browser

A full-screen kiosk browser for Android. Point it at a dashboard (Home Assistant, Grafana, a status
page, anything on the web or your LAN) and it keeps that page on screen: no browser bars, no
wandering off to other sites, and it recovers by itself when the network or the page misbehaves.

It runs on a phone or tablet with Android 8.0 or newer. Personal dashboards need no special setup.
For public devices, optional [managed lockdown](docs/MANAGED-KIOSK.md) blocks Android's normal exits.

## Features

- **Full screen**: status and navigation bars hidden, notch area used, screen kept on.
- **Stays on your site**: links to other sites, `tel:`, `mailto:`, app links and the like are
  blocked. Extra sites (a single sign-on page, say) can be allowed, including `*.example.com`.
- **LAN friendly**: plain `http://` addresses work, and self-signed HTTPS certificates can be trusted
  after checking their fingerprint. A changed certificate is flagged, never accepted silently.
- **Stays signed in**: cookies and site storage survive restarts. HTTP basic auth prompts are
  supported and remembered.
- **Camera and microphone**: a page on an allowed site can use them (for an intercom or voice
  assistant, say) once the administrator allows it. Optionally, pages can autoplay with sound.
- **Recovers on its own**: if the page can't load it shows why and retries (sooner when the network
  comes back), rebuilds the browser if it crashes, reloads a page that has frozen, and can go back to
  the home page after a period without touches or reload on a timer.
- **Screen schedule**: black out the screen overnight or on chosen days; a touch wakes it.
- **Screensaver and sleep**: after a while without use, show a drifting clock, the dashboard dimmed
  or another page, and later turn the screen off.
- **Wake on approach**: optionally wake when the camera sees movement or something comes near the
  proximity sensor. Camera pictures never leave the device.
- **Reduce burn-in risk**: optionally nudges the page a few pixels every couple of minutes.
- **Home Assistant**: connects over MQTT and shows up as a device with a screen light (on/off and
  brightness), buttons, volume, notifications, text-to-speech and sensors. No YAML needed.
- **Remote control**: an optional HTTP API for the same commands and more, like reloading, opening a
  page for a while (a doorbell camera, say), messages, sounds, speech and screenshots.
- **Home-screen mode**: optionally make it the phone's home app so it comes back after a reboot.
- **Administrator access**: five corner taps open settings, with an optional PIN.
- **Managed lockdown**: Device Owner or a device management provider can allowlist dKiosk for
  Android's lock task mode, blocking Home, Recents and system controls.

## Install

1. Download the latest `dkiosk-<version>.apk` from the
   [Releases page](https://github.com/byyako/dkiosk/releases/latest).
2. Open it on the device. Android will ask you to allow installs from your browser or file manager.
3. Open dKiosk, enter the page address and choose whether to require an administrator PIN.

**Updating:** install the new APK over the old one; settings and sign-ins are kept. dKiosk doesn't
update itself, but [Obtainium](https://github.com/ImranR98/Obtainium) can watch the Releases page
for you.

The APK is signed with a certificate whose SHA-256 fingerprint is
`d15215450edf0be53dc610833fa0bad88e77f89573aaa3babf40d22d8cc51313`. An APK signed with anything else
didn't come from here.

## Using it

**Settings:** tap the top-right corner five times within five seconds. Progress appears after each
tap, and a PIN is requested if protection is enabled. The 80 dp corner area is reserved for this
gesture, so dashboard controls there do not receive touches. Drags, long presses, multiple fingers
and touches outside the corner reset the sequence. The gesture works on the error screen too.

**Optional PIN:** turn "Require administrator PIN" on or off in settings. Existing installs keep
their PIN protection. Without a PIN, anyone who knows the gesture can change settings, trust
certificates and exit, including in managed lockdown.

**Camera and microphone:** the first time a page asks, dKiosk asks whether to allow it. Allowing
needs the PIN if protection is on; blocking doesn't. Both answers are remembered per site, and
"Forget camera and microphone answers" in the settings resets them. Browsers only let secure pages
use them: `https://` addresses, or `localhost`. A dashboard on plain `http://192.168.x.x` can't,
whatever the kiosk allows.

**Forgot the PIN?** Clear dKiosk's storage in Android's app settings (Settings, Apps, dKiosk Browser,
Storage). That resets everything, including the sign-in to your page.

**Leaving the kiosk:** use "Exit kiosk" in settings. This turns managed lockdown off before leaving.
In home-screen mode, choose another home app when prompted. Ordinary installs also allow Android's
Home/Recents gestures; public devices need managed provisioning to block those exits.

**Start after a reboot:** turn on "Use as home screen" in the settings and pick dKiosk Browser as the
home app. Android only lets a home app start by itself after a reboot.

## Home Assistant

dKiosk uses Home Assistant's MQTT discovery, so it appears as a device by itself:

1. Set up the [MQTT integration](https://www.home-assistant.io/integrations/mqtt/) with a broker, for
   example the Mosquitto add-on, and create a Home Assistant user for the kiosk to log in with.
2. In dKiosk's settings, turn on "Connect to Home Assistant" and enter the broker's address, the user
   name and the password. "Connection" shows whether it worked the next time you open the settings.
3. Go back to the dashboard. The kiosk appears under Settings, Devices, MQTT.

| Entity | Does |
|---|---|
| Screen (light) | Turns the screen off and on; brightness sets the screen brightness |
| Automatic brightness, Reload, Go home (buttons) | What they say |
| Volume (number) | Media volume |
| Page (text) | Shows the current page; set it to open another allowed page |
| Play sound (text) | Plays a sound file from an http(s) address; `stop` stops it |
| Message, Speak (notify) | An on-screen message, or text read aloud |
| Battery, Charging, Current page, Load error, Last touch, Screensaver (sensors) | Last touch is handy for presence |
| Motion (binary sensor) | Movement seen by the camera or proximity sensor, held for 30 seconds. Only with one of them on |
| Take screenshot (button), Screenshot (image) | Only while "Allow remote screenshots" is on |

The Page entity also takes `{"url": "...", "seconds": 30}` to show a page for a while, and Message
takes `{"text": "...", "seconds": 0}` to keep a message up until it's tapped. MQTT can't change the
home page. The connection is up while the dashboard is visible; Home Assistant shows the kiosk as
unavailable while its settings are open. TLS needs a broker certificate Android trusts, issued for
the address entered.

```yaml
# Show the doorbell camera for 30 seconds and announce it.
action:
  - action: text.set_value
    target:
      entity_id: text.hall_kiosk_page
    data:
      value: '{"url": "http://homeassistant.local:8123/doorbell-view", "seconds": 30}'
  - action: notify.send_message
    target:
      entity_id: notify.hall_kiosk_speak
    data:
      message: Someone is at the door
```

## Remote control API

Turn on "HTTP API" in the settings. The settings show the address (like `http://192.168.1.50:8765`)
and an access token. Every request needs the header `Authorization: Bearer <token>`. The API only
works while the dashboard is visible; it stops in settings and in the background. It is plain HTTP,
so keep it on a network you trust.

| Request | Body | Does |
|---|---|---|
| `GET /status` | | Current page, home page, screen state, screensaver, motion, brightness, volume, last load error, battery |
| `GET /screenshot` | | A JPEG of the screen. Off until "Allow remote screenshots" is turned on in the settings |
| `POST /reload` | | Reloads the page |
| `POST /home` | | Goes to the home page |
| `POST /url` | `{"url": "https://...", "home": false}` | Opens a page. It has to be an allowed site unless `"home": true`, which also makes it the new home page |
| `POST /url` | `{"url": "https://...", "seconds": 30}` | Shows an allowed page for a while, like a doorbell camera, then goes back. Lights a dark screen meanwhile, and waits while someone is touching the screen |
| `POST /screen` | `{"state": "off"}` or `{"state": "on"}` | Blacks out or wakes the screen until the schedule next switches |
| `POST /brightness` | `{"level": 60}` or `{"level": "auto"}` | Sets the brightness from 1 to 100%, or back to Android's setting. Saved, and shown in the settings |
| `POST /volume` | `{"level": 50}` | Sets the media volume from 0 to 100% |
| `POST /speak` | `{"text": "Someone is at the door", "language": "en-US"}` | Speaks the text. Needs a text-to-speech engine on the device; `language` is optional |
| `POST /sound` | `{"url": "http://..."}` or `{"stop": true}` | Plays a sound file from any address, or stops it |
| `POST /message` | `{"text": "Dinner's ready", "seconds": 10}` | Shows a message at the bottom of the screen (`0` keeps it until tapped). Lights a dark screen while it's up |

Sounds and speech stop when the settings are opened or dKiosk goes to the background.

```sh
curl -H "Authorization: Bearer $TOKEN" http://192.168.1.50:8765/status
curl -X POST -H "Authorization: Bearer $TOKEN" -d '{"state": "off"}' http://192.168.1.50:8765/screen
curl -H "Authorization: Bearer $TOKEN" -o screen.jpg http://192.168.1.50:8765/screenshot
```

### Home Assistant without MQTT

```yaml
# configuration.yaml
rest_command:
  kiosk_reload:
    url: http://192.168.1.50:8765/reload
    method: post
    headers:
      Authorization: !secret kiosk_auth
  kiosk_screen:
    url: http://192.168.1.50:8765/screen
    method: post
    headers:
      Authorization: !secret kiosk_auth
    content_type: application/json
    payload: '{"state": "{{ state }}"}'

sensor:
  - platform: rest
    name: Kiosk battery
    resource: http://192.168.1.50:8765/status
    headers:
      Authorization: !secret kiosk_auth
    value_template: "{{ value_json.battery }}"
    unit_of_measurement: "%"
    device_class: battery
```

```yaml
# secrets.yaml
kiosk_auth: "Bearer your-token-here"
```

Then call `rest_command.kiosk_screen` with `state: "off"` from an automation, for example when
everyone has left the house.

## Tips for a wall-mounted phone

- A phone that is always plugged in can have its battery swell over the months. If your ROM has a
  charging limit (stop at 80%), turn it on.
- A static dashboard on an OLED screen can burn in. Turn on "Reduce burn-in risk" and the screen
  schedule.
- Turn off battery optimization for dKiosk if the phone's ROM kills apps in the background.

## Building

You need JDK 17 and the Android SDK (platform 37, build tools 36). Then:

```sh
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # unit tests
```

Debug builds install as a separate app ("dKiosk (debug)") next to the release build. See
[docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) for release signing, the local test site and on-device
testing, and [docs/DESIGN.md](docs/DESIGN.md) for the design notes.

## Support

dKiosk is free. If it's useful to you, you can [buy me a coffee](https://buymeacoffee.com/tylernol).
Bug reports and ideas are welcome in [Issues](https://github.com/byyako/dkiosk/issues); security
problems go through [SECURITY.md](SECURITY.md).

## License

[MIT](LICENSE)
