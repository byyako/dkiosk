# Design notes

Why dKiosk works the way it does. For how to build and test it, see [DEVELOPMENT.md](DEVELOPMENT.md).

## Scope

dKiosk shows one web page full screen on an Android device. Ordinary installs suit personal
dashboards and allow Android's system gestures to leave the app. Public devices can opt into
managed lock task mode after Device Owner provisioning or a device manager's allowlist approval.
The app never substitutes screen pinning when management is absent, because users can exit it.
See [MANAGED-KIOSK.md](MANAGED-KIOSK.md) for provisioning and acceptance checks.

Every install chooses its page on first run. Administrator PIN protection defaults to on and is
optional, independently of managed lockdown. Existing installs preserve their PIN.

## Navigation

Top-level pages must be on the home page's host (any port or scheme, `www.` ignored) or on an extra
allowed host. Extra hosts exist mainly for single sign-on pages; `*.example.com` covers a domain and
its subdomains. Frames are not restricted, because dashboards embed charts, cameras and players from
other hosts. Non-web schemes (`tel:`, `intent:`, `market:`, `file:`...) are always blocked, even
with the restriction turned off, since they would take the user out of the kiosk.

`shouldOverrideUrlLoading` doesn't see form POSTs, so `onPageStarted` checks every page again. When
a load the kiosk started itself (the home page, a retry, a reload) ends up on a blocked host,
typically because the home page redirected to a login on another site, the error screen explains
which host to allow instead of leaving a blank or stale page. It retries with the usual backoff
rather than loading the home page again straight away, which would loop.

Host parsing is done by hand rather than with `java.net.URI`, which rejects URLs WebView accepts.

## Certificates and logins

Plain `http://` is allowed everywhere because LAN dashboards use it and network security config
can't express IP ranges.

Self-signed certificates are trusted per host and exact SHA-256 fingerprint, after showing the
fingerprint and asking for the PIN when protection is enabled. A different certificate for the same host gets a "changed"
warning. `SslErrorHandler.proceed()` is never called unconditionally.

Camera and microphone requests follow the same rules as certificates: only allowed hosts may ask,
allowing needs the PIN, and the answer is remembered per host and feature. Blocking needs no PIN
because it can't hurt, and remembering it stops a page asking a passer-by over and over. Android's
own permission is requested only after an administrator allows a site, so a kiosk that never uses
the camera never asks for it. Android's permission dialog pauses the activity, which dismisses the
kiosk's prompts, so a request already allowed is held outside them until Android answers. Location
and file uploads aren't supported: a dashboard rarely needs them, and the file picker would leave
the kiosk.

Basic auth credentials go in WebView's own database. Only allowed hosts can prompt; other hosts'
requests are cancelled, including requests with previously saved credentials or certificate pins.
Pending prompts are cancelled when the activity pauses or the renderer is replaced. Backups and
device-to-device transfer are disabled so none of this, or the
cookies, ends up on another device.

## Recovery

A kiosk nobody watches has to get itself out of trouble:

- Main-frame network errors, 5xx responses and loads that take over 60 seconds show an error screen
  that retries with backoff (5 s doubling to 60 s), and right away when the network comes back.
  (Registering for network changes reports the current network at once, so only a network that
  returns after being lost counts.) The timer starts when the kiosk starts a load, not only when the
  page commits, so a server that accepts the connection and never answers is caught.
- WebView reports a load that was dropped before the server answered (replaced by a retry, or
  blocked) through `onPageFinished`, the same as one that loaded. A finish only counts once a page
  has started since the kiosk's last load; otherwise every retry would hide the error screen and
  cancel its own timeout. Loading the current page with another `#fragment` starts no page, so it's
  exempt.
- Errors are matched to pages by URL in `onPageFinished`, because HTTP errors arrive before
  `onPageStarted`.
- `onRenderProcessGone` replaces the WebView. Repeated crashes within a minute back off so a page
  that crashes on load can't spin.
- A JavaScript ping every 30 seconds catches a frozen page, which WebView doesn't report when nobody
  is touching the screen. It pauses while an `alert()` is open, and alerts close themselves after a
  minute so a dashboard can't stay frozen behind one.
- After a period without touches the kiosk goes back to the home page, and it can reload on a timer.

## Screen

"Off" means a black overlay plus zero window brightness. The page keeps running, so it's current
when the screen wakes, and on OLED screens black pixels are off anyway. Really turning the screen
off would need device admin rights and would leave the phone locked.

`ScreenState` combines three inputs: the schedule, a remote on/off that lasts until the schedule
next switches, and a touch that wakes the screen for a while. The touch that wakes the screen is
swallowed so it can't press anything on the page.

## Settings access

Five completed taps within five seconds in a reserved 80 dp top-right corner, then the optional PIN.
The area consumes touches so page controls cannot interrupt the sequence. Progress is visible;
movement beyond Android's touch slop, long presses, cancelled gestures, multitouch and outside
touches reset it. The area sits flush in the corner, where people look for it: Android's gesture
areas only take swipes, so taps there still arrive. It moves down only if a camera cutout overlaps it.
An accessibility click on the administrator target opens the same optional PIN flow.
The PIN is stored as a salted PBKDF2 hash.
Five wrong tries lock the prompt for 30 seconds, doubling after that.

Home-screen mode puts the HOME intent filter on an activity alias that is disabled by default.
If it were on the main activity, installing the app could make Android ask which home app to use.

## Remote control

A tiny HTTP/1.1 server written for the purpose: one JSON request per connection, size limits, a
10-second read deadline and a bounded worker queue. NanoHTTPD would have been the usual choice, but
it's unmaintained and has open CVEs. Every request needs a bearer token, compared in constant time.
The server runs only while the dashboard activity is resumed, so old tokens cannot keep serving
requests while administrators edit settings. Shutdown closes queued and active sockets. Main-thread
commands are cancelled if they time out before execution. Android 10+ doesn't let a background app bring itself to the
front, so a background server couldn't do much anyway.

Screenshots use `PixelCopy`, because drawing the view hierarchy misses the WebView's hardware
layers. The copy finishes on a background thread so the API worker waits for it, not the main thread.
They're off by default: a token holder could otherwise read whatever the dashboard shows.

Messages and temporary pages light a dark screen for as long as they're up, since nobody would see
them otherwise. A temporary page goes back to the page it replaced; a touch postpones that until the
screen has been left alone for 15 seconds, so it isn't pulled away from someone using it. Loading the
home page for any reason ends it.

The brightness setting is the window's brightness, not Android's, so it needs no permission and
only applies while dKiosk is in front.

## Home Assistant

MQTT discovery rather than a custom integration: it needs nothing installed in Home Assistant beyond
the MQTT integration most installs already have, and the device and its entities appear by
themselves. The client is a small MQTT 3.1.1 implementation written for the purpose, like the HTTP
server: Eclipse Paho for Android is unmaintained and HiveMQ's client brings Netty and RxJava. It
publishes and subscribes at QoS 0 over one connection, with a retained last will so Home Assistant
marks the kiosk unavailable if it drops. TLS uses Android's trusted certificates and checks the host
name, which plain `SSLSocket` doesn't do by itself.

Commands go through the same `KioskControl` as the HTTP API, on a worker thread because those calls
wait for the main thread. The connection follows the dashboard's lifecycle like the API does. MQTT
deliberately can't change the home page: anyone who can publish on the broker shouldn't be able to
repoint the kiosk for good. When Home Assistant restarts it publishes `online` on
`homeassistant/status`, and the kiosk announces itself again.

## Android versions

minSdk 26 (Android 8.0), targetSdk 36, compileSdk 37.

Targeting 37 is deliberately deferred. Android 17 requires the `ACCESS_LOCAL_NETWORK` runtime
permission for LAN traffic, and that covers both WebView's requests and incoming API connections.
Before raising targetSdk, the first-run screen has to request that permission.
