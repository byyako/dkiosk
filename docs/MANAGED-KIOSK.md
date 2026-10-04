# Managed public kiosks

Personal installs and public kiosks use the same app. "Managed lockdown" is off by default and uses
Android's [lock task mode](https://developer.android.com/work/dpc/dedicated-devices/lock-task-mode).
It requires Device Owner provisioning or an existing device manager that allowlists the app.
Installing the APK or granting ordinary device administrator rights does not provide lockdown.

PIN protection is a separate, optional setting. For an unattended public kiosk, enable it unless
anyone who knows the corner gesture should be able to administer and exit the device. Without a
PIN, the gesture permits settings changes, certificate approval and leaving lockdown.

## Provisioning a dedicated device

Provisioning is a deliberate administrator operation. Back up the device first. Device Owner setup
usually requires a factory reset and must happen before accounts are added. Follow Android's
[dedicated device provisioning guide](https://developer.android.com/work/dpc/dedicated-devices).
No reset or device provisioning is performed by dKiosk's settings screen.

For a development device eligible for adb provisioning, install the app and use the appropriate
package identifier:

```sh
# Release install:
adb shell dpm set-device-owner com.byyako.dkiosk/.lockdown.KioskDeviceAdminReceiver
# Debug install (separate package):
adb shell dpm set-device-owner com.byyako.dkiosk.debug/com.byyako.dkiosk.lockdown.KioskDeviceAdminReceiver
```

Only run the command for the installed variant you intend to manage. Android rejects ineligible
devices. Do not reset an existing personal device just to try this feature.

If the device already has a management provider, ask it to allowlist `com.byyako.dkiosk` (or
`com.byyako.dkiosk.debug` for testing) for lock task mode. Keep that provider as Device Owner;
dKiosk does not replace it. The provider controls any additional restrictions and recovery process.

## Configure and start

1. Set up the shared dashboard and choose whether administrator PIN protection is enabled.
2. Before starting lockdown, enable "Use as home screen" and choose dKiosk as the default home app
   if it should return after reboot. Lockdown alone does not configure startup or a default launcher.
3. Open settings, enable "Managed lockdown", then return to the dashboard. The switch is unavailable
   unless dKiosk is Device Owner or the device manager has allowed lock task mode.
4. Test exits and administrator recovery on that exact device before putting it in public use.

When dKiosk is Device Owner it allowlists itself and, on Android 9+, disables lock task system UI
features. With an external manager, that manager controls system UI policy. Android 8 remains
installable; use an Android/WebView version still receiving security updates for public deployments.
The app does not use user-escapable screen pinning as a fallback.

## Administrator exit

Tap the top-right corner five times within five seconds and enter the PIN if enabled. Select
"Exit kiosk" and confirm. dKiosk ends the lock task session it started and turns the lockdown setting
off. If it is the default home app, Android's home app chooser opens next so another launcher can be
selected. Re-enable managed lockdown before returning the device to public use.

If an external device manager started and owns the session, it may have to release it. Keep the
device manager's recovery procedure available. A forgotten PIN requires administrator recovery;
clearing app storage also deletes dashboard configuration and sign-ins. Do not rely on that being
available to visitors in lockdown.

Lock task mode controls normal software exits. It does not prevent physical power-off, recovery
mode, exposed USB debugging or changes made by another device administrator. Apply deployment
policies appropriate to the hardware and keep administrative access under your control.

## Acceptance checks

Before rollout, verify on each supported Android/device combination:

- Fresh setup with PIN enabled and disabled survives app and device restarts. Upgrading preserves
  an existing PIN. Enable, change, disable and re-enable PIN protection in settings.
- Five completed taps reliably open settings in portrait and landscape, with cutouts, a keyboard,
  the error screen and a recently woken screen. The wake touch does not count. Dashboard buttons
  under the reserved corner do not activate. Drags, long presses and multitouch do not count.
- Wrong PINs never unlock settings; five failures trigger lockout. Current lockout is process-local,
  so restarting the process resets it. Do not treat this as persistent brute-force protection.
- Home, Recents, notification shade, Back and system gestures cannot leave a managed dashboard.
  Test OEM gestures, power menus and connected keyboards too. Ordinary installs must not claim
  they provide managed lockdown.
- Administrator exit works and leaves lockdown off. Choosing another home app works after exit.
  Returning to a kiosk with lockdown enabled, renderer recovery and activity recreation keep it
  contained. Reboot returns to dKiosk only when home-screen mode is configured.
- An external manager's allowlist and UI policy behave as expected; removing approval is detected.
- The API stops while settings are open, resumes with the new token, and closes active and queued
  sockets on shutdown. Removed hosts cannot reuse saved basic-auth credentials or certificate pins.

Automated JVM checks cover gesture recognition, API authorization, socket shutdown and URL routes.
They do not prove Android containment or physical touch behavior; those require the device checks.
