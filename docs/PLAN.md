# dKiosk Browser — plan

Single-purpose fullscreen browser (`com.tyllad.dkiosk`). One configurable URL, self-healing,
screen schedule, LAN HTTP API. Built to be shared: nothing personal is hardcoded; every install is
configured on first run.

## Decisions
| Area | Decision |
|---|---|
| Lockdown | Immersive fullscreen only (no Device Owner / screen pinning). Runs on a normal phone. |
| Content | One home URL. Navigation locked to its origin (scheme+host+port); extra domains configurable (for SSO logins). |
| Logins | Cookies + DOM storage persisted; `CookieManager.flush()` on pause. |
| Cleartext | Allowed app-wide (`network_security_config.xml`) because LAN IPs can't be listed per-domain. |
| Self-signed TLS | Trust-on-first-use: user approves the cert's SHA-256 fingerprint behind the PIN; only that exact cert is accepted afterward. Never blanket `proceed()`. |
| Screen "off" | Black overlay + minimum window brightness (AMOLED ⇒ pixels actually off). Touch or API wakes it. |
| Remote control | NanoHTTPD server inside the app, bearer-token auth, off until enabled. Lives with the Activity (Android 10+ blocks background activity starts, so it only works while the app is open). |
| Toolchain | AGP 9.4 (built-in Kotlin), Gradle 9.8, JDK 17, compileSdk 37, targetSdk 36, minSdk 26. |

## Phases
1. ✅ Toolchain + skeleton — immersive WebView, first-run URL prompt, cookie persistence, cleartext, keyboard insets.
2. Web hardening — origin lock + extra-domain allowlist, non-web schemes (tel:, intent:) blocked, TOFU certs, render settings (zoom, user agent) configurable.
3. Auto-recovery — offline screen + backoff retry, `NetworkCallback` reload, `onRenderProcessGone` rebuild, JS heartbeat watchdog, idle → home, periodic reload.
4. Admin panel — 5 taps top-right + PIN (hashed), PreferenceFragmentCompat settings, proper first-run wizard, exit.
5. Screen schedule — overlay, overnight windows (23:00–07:00), touch-to-wake timeout; unit-tested window logic.
6. HTTP API — `GET /status`, `POST /reload`, `POST /url {url, persist}`, `POST /home`, `POST /screen {state}`; Home Assistant `rest_command` example.
7. Polish / distribution — release signing (keystore outside repo), README, versioning, optional burn-in pixel shift.

## Test recipes
- Network loss: `adb shell svc wifi disable` / `enable`
- Renderer crash: load `chrome://crash`
- Reset to first run: `adb shell pm clear com.tyllad.dkiosk`
