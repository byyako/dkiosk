# dKiosk Browser

A full-screen kiosk browser for Android. Point it at a dashboard (Home Assistant, Grafana, a status
page, anything on the web or your LAN) and it keeps that page on screen: no browser bars, no
wandering off to other sites, and it recovers by itself when the network or the page misbehaves.

It runs on an ordinary phone or tablet with Android 8.0 or newer. No root, no device owner setup.

## Features

- **Full screen**: status and navigation bars hidden, notch area used, screen kept on.
- **Stays on your site**: links to other sites, `tel:`, `mailto:`, app links and the like are
  blocked. Extra sites (a single sign-on page, say) can be allowed, including `*.example.com`.
- **LAN friendly**: plain `http://` addresses work, and self-signed HTTPS certificates can be trusted
  after checking their fingerprint. A changed certificate is flagged, never accepted silently.
- **Stays signed in**: cookies and site storage survive restarts. HTTP basic auth prompts are
  supported and remembered.
- **Recovers on its own**: if the page can't load it shows why and retries (sooner when the network
  comes back), rebuilds the browser if it crashes, reloads a page that has frozen, and can go back to
  the home page after a period without touches or reload on a timer.
- **Screen schedule**: black out the screen overnight or on chosen days; a touch wakes it.
- **Burn-in protection**: optionally nudges the page a few pixels every couple of minutes.
- **Remote control**: an optional HTTP API for reloading, changing the page and switching the screen
  on or off, for example from Home Assistant.
- **Home-screen mode**: optionally make it the phone's home app so it comes back after a reboot.
- **Locked settings**: everything is behind a PIN.

## Install

1. Download the latest APK from the Releases page.
2. Open it on the device. Android will ask you to allow installs from your browser or file manager.
3. Open dKiosk, enter the page address and choose a settings PIN.

## Using it

**Settings:** tap the top-right corner of the screen five times quickly, then enter the PIN. Taps
still reach the page, so buttons in that corner keep working.

**Forgot the PIN?** Clear dKiosk's storage in Android's app settings (Settings, Apps, dKiosk Browser,
Storage). That resets everything, including the sign-in to your page.

**Leaving the kiosk:** use "Exit kiosk" in the settings, or the normal Home/recents gestures. In
home-screen mode, pick another home app first (the settings screen takes you there).

**Start after a reboot:** turn on "Use as home screen" in the settings and pick dKiosk Browser as the
home app. Android only lets a home app start by itself after a reboot.

## Remote control API

Turn on "HTTP API" in the settings. The settings show the address (like `http://192.168.1.50:8765`)
and an access token. Every request needs the header `Authorization: Bearer <token>`. The API only
works while dKiosk is open, and it's plain HTTP, so keep it on a network you trust.

| Request | Body | Does |
|---|---|---|
| `GET /status` | | Current page, home page, screen state, last load error, battery |
| `POST /reload` | | Reloads the page |
| `POST /home` | | Goes to the home page |
| `POST /url` | `{"url": "https://...", "home": false}` | Opens a page. It has to be an allowed site unless `"home": true`, which also makes it the new home page |
| `POST /screen` | `{"state": "off"}` or `{"state": "on"}` | Blacks out or wakes the screen until the schedule next switches |

```sh
curl -H "Authorization: Bearer $TOKEN" http://192.168.1.50:8765/status
curl -X POST -H "Authorization: Bearer $TOKEN" -d '{"state": "off"}' http://192.168.1.50:8765/screen
```

### Home Assistant

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
- A static dashboard on an OLED screen can burn in. Turn on "Prevent burn-in" and the screen
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
testing, and [docs/PLAN.md](docs/PLAN.md) for the design notes.

## License

[MIT](LICENSE)
