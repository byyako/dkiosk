# Development

dKiosk is a single-module Android app in Kotlin: one activity hosting the system WebView, plus the
setup and settings screens. Design decisions and their reasons are in [DESIGN.md](DESIGN.md).

## Layout

```
app/src/main/java/com/byyako/dkiosk/
  KioskActivity.kt      the kiosk: WebView, immersive mode, wiring for everything below
  config/               KioskPrefs, every setting in one place
  lockdown/             optional Device Owner / allowlisted lock task mode
  web/                  navigation rules, certificate pins, login and certificate prompts, WebViewClient
  recovery/             error screen, retry backoff, hang watchdog, idle timer
  screen/               screen schedule, blackout, burn-in shift
  remote/               the HTTP API (tiny HTTP server, routing, token check)
  settings/             PIN, settings screen, home-screen mode
  setup/                first-run screen
  ui/                   inset helpers
testsite/               local pages and scripts for testing on a device
```

Logic that doesn't need Android (URL rules, the schedule, PIN hashing, HTTP parsing, API routing)
lives in plain classes with JVM unit tests under `app/src/test`.

## Building

JDK 17 and the Android SDK (platform `android-37.0`, build tools `36.0.0`, platform tools) are needed.
Point `local.properties` at the SDK (`sdk.dir=...`) or set `ANDROID_HOME`.

```sh
./gradlew assembleDebug testDebugUnitTest lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.byyako.dkiosk.debug/com.byyako.dkiosk.KioskActivity
```

Debug builds use the application ID `com.byyako.dkiosk.debug`, so they install next to a release
build. Versions live in `gradle/libs.versions.toml`. AGP 9 compiles Kotlin itself, so don't add the
`org.jetbrains.kotlin.android` plugin.

Gradle's build cache is off on purpose: AGP's resource merge reused a stale, empty output after a
resource folder was renamed. Lint can also report stale results after a manifest-only change; run
`./gradlew lintDebug --rerun-tasks` if a warning looks out of date.

## Release builds

Signing details go in `keystore.properties` in the project root. It is gitignored, and so is any
`*.jks`/`*.keystore` file:

```properties
storeFile=/path/outside/the/repo/dkiosk-release.jks
storePassword=...
keyAlias=dkiosk
keyPassword=...
```

```sh
./gradlew assembleRelease
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

Keep a backup of the keystore and its password. Without them no update can be installed over
existing installs; people would have to uninstall and set the kiosk up again.

To publish: bump `versionCode` and `versionName` in `app/build.gradle.kts`, build, rename the APK to
`dkiosk-<version>.apk` and attach it to a release.

## Testing on a device

### Local test site

`testsite/serve.py` serves test pages over plain HTTP on port 8780 and self-signed HTTPS on 8781:
links of every kind, a basic auth area (`/private/`, user `kiosk`, password `letmein`), a page that
always fails (`/503`), one that never answers (`/hang`), a redirect to another host (`/away`), a
download and page dialogs. `adb reverse` makes them reachable from a USB-connected phone without
touching any firewall:

```sh
python -u testsite/serve.py              # --new-cert rotates the certificate
adb reverse tcp:8780 tcp:8780
adb reverse tcp:8781 tcp:8781
```

Then use `http://localhost:8780/` as the home page.

### Driving the page

Debug builds allow WebView debugging, so the page can be scripted over the DevTools protocol:

```sh
adb forward tcp:9222 localabstract:webview_devtools_remote_$(adb shell pidof com.byyako.dkiosk.debug)
node testsite/cdp.mjs "location.href"
node testsite/cdp.mjs "document.getElementById('tel').click()"
```

### Recipes

| What | How |
|---|---|
| Blocked links | Click each link on the test page; the kiosk should stay put and show a message |
| Self-signed certificate | Open "Self-signed HTTPS"; restart the server with `--new-cert` to see the "changed" warning |
| Server down | Point the home page at a port nothing listens on; the error screen should count down and retry |
| 5xx | Open "HTTP 503" |
| Server that never answers | Make `http://localhost:8780/hang` the home page; the error screen appears after 60 s and keeps retrying |
| Home page redirected off-site | Make `http://localhost:8780/away` the home page; the error screen says to allow 127.0.0.1 |
| Page dialogs | "alert()" closes itself after a minute; "Leave with beforeunload" leaves without asking |
| Renderer crash | `node testsite/cdp.mjs --method Page.crash` |
| Frozen page | `node testsite/cdp.mjs --timeout 2000 "setTimeout(() => { while (true) {} }, 100)"`, recovers within about 45 s |
| Network loss | `adb shell svc wifi disable`, then `enable` |
| API | `adb forward tcp:8765 tcp:8765`, then `curl -H "Authorization: Bearer $TOKEN" localhost:8765/status`; `POST /url` with `"home": true` is the quickest way to switch the home page |
| First run again | `adb shell pm clear com.byyako.dkiosk.debug` |

For optional PIN, corner gesture and managed lockdown acceptance checks, see
[MANAGED-KIOSK.md](MANAGED-KIOSK.md). Provisioning belongs on a dedicated test device and is separate
from normal build/install testing.

Settings can be written directly in debug builds with
`adb shell run-as com.byyako.dkiosk.debug` (file `shared_prefs/kiosk.xml`) to skip the UI.

## Validation checkpoint: 2026-10-03

The optional PIN, corner gesture and managed lockdown changes pass `assembleDebug
testDebugUnitTest lintDebug` (82 JVM tests; lint only reports the deliberately deferred targetSdk).
On a OnePlus 6T (LineageOS, Android 15, not provisioned) the debug build passed: first-run setup
with a PIN, the corner gesture in portrait and landscape and over the error screen (drags and long
presses don't count, progress resets), wrong-PIN lockout, turning PIN protection off and on, the
API pausing in settings and in the background, Bearer token checks, Exit kiosk, blocked links,
self-signed certificate trust behind the PIN and basic auth. Settings written in the 1.0.0 format
(home page and PIN hash only) skip setup and keep requiring the PIN.

Not yet checked on a device: Device Owner provisioning and everything in lockdown, and reboot with
home-screen mode. Complete the acceptance checks in
[MANAGED-KIOSK.md](MANAGED-KIOSK.md) before releasing or using this on a public device.
