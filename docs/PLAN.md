# dKiosk Browser — plan

Single-purpose fullscreen browser (`com.tyllad.dkiosk`). One configurable URL, self-healing,
screen schedule, LAN HTTP API. Built to be shared: nothing personal is hardcoded; every install is
configured on first run.

## Decisions
| Area | Decision |
|---|---|
| Lockdown | Immersive fullscreen only (no Device Owner / screen pinning). Runs on a normal phone. |
| Content | One home URL. Top-level pages locked to its host (any port/scheme, "www." ignored) plus configurable extra hosts (`*.example.com` wildcards) for SSO logins. Iframes may come from anywhere. Non-web schemes (tel:, intent:, …) always blocked. Can be switched to open browsing. |
| Logins | Cookies + DOM storage persisted; `CookieManager.flush()` on pause. |
| Cleartext | Allowed app-wide (`network_security_config.xml`) because LAN IPs can't be listed per-domain. |
| Self-signed TLS | Trust-on-first-use: user approves the cert's SHA-256 fingerprint behind the PIN; only that exact cert is accepted afterward. Never blanket `proceed()`. |
| Screen "off" | Black overlay + minimum window brightness (AMOLED ⇒ pixels actually off). Touch or API wakes it. |
| Remote control | NanoHTTPD server inside the app, bearer-token auth, off until enabled. Lives with the Activity (Android 10+ blocks background activity starts, so it only works while the app is open). |
| Toolchain | AGP 9.4 (built-in Kotlin), Gradle 9.8, JDK 17, compileSdk 37, targetSdk 36, minSdk 26. |
| targetSdk 37 | Deliberately deferred. Targeting 37 makes WebView LAN traffic *and* incoming API connections need the `ACCESS_LOCAL_NETWORK` runtime permission; request it in the first-run wizard before bumping. |

## Phases
1. ✅ Toolchain + skeleton — immersive WebView, first-run URL prompt, cookie persistence, cleartext, keyboard insets.
2. ✅ Web hardening — host lock + extra-host allowlist, non-web schemes blocked (toast explains), onPageStarted backstop for POSTs, TOFU cert pins with "changed" warning, zoom / user agent / restriction prefs (UI comes in phase 4).
3. Auto-recovery — offline screen + backoff retry, `NetworkCallback` reload, `onRenderProcessGone` rebuild, JS heartbeat watchdog, idle → home, periodic reload.
4. Admin panel — 5 taps top-right + PIN (hashed), PreferenceFragmentCompat settings (home URL, allowed hosts, restriction, zoom, user agent, clear cert pins), proper first-run wizard, exit. Gate the cert-trust dialog behind the PIN once it exists.
5. Screen schedule — overlay, overnight windows (23:00–07:00), touch-to-wake timeout; unit-tested window logic.
6. HTTP API — `GET /status`, `POST /reload`, `POST /url {url, persist}`, `POST /home`, `POST /screen {state}`; Home Assistant `rest_command` example.
7. Polish / distribution — release signing (keystore outside repo), README, versioning, optional burn-in pixel shift.

## Test recipes
- Test site: `python -u testsite/serve.py` (HTTP :8780, self-signed HTTPS :8781; `--new-cert` rotates the cert),
  then `adb reverse tcp:8780 tcp:8780` and `adb reverse tcp:8781 tcp:8781`; home URL `http://localhost:8780/`.
- Drive the page (debug builds): `adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>`, then
  `node testsite/cdp.mjs "document.getElementById('tel').click()"`.
- Network loss: `adb shell svc wifi disable` / `enable`
- Renderer crash: `node testsite/cdp.mjs --method Page.crash`
- Hung page: `node testsite/cdp.mjs --timeout 2000 "setTimeout(() => { while (true) {} }, 100)"` (recovers in ~45 s)
- Basic auth: `/private/` on the test site; 5xx: `/503`
- Reset to first run: `adb shell pm clear com.tyllad.dkiosk`
