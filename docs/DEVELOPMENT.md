# dKiosk Browser

Android kiosk browser (Kotlin, single Activity + system WebView). Roadmap and design decisions:
[docs/PLAN.md](docs/PLAN.md) — keep its phase checklist current.

## Build & run (Windows)
JDK and SDK are user-local installs; nothing is on PATH.
```powershell
$env:JAVA_HOME="$env:LOCALAPPDATA\Programs\Temurin\jdk-17.0.20.1+1"
.\gradlew.bat assembleDebug testDebugUnitTest
```
```
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.tyllad.dkiosk/.KioskActivity
```
`MSYS_NO_PATHCONV=1` or device paths like `/sdcard/...` get rewritten to Windows paths.

SDK packages: use `cmdline-tools/latest/bin/android.exe sdk install build-tools/36.0.0` (slash paths;
`sdkmanager` is deprecated and `;` breaks through the .bat wrapper).

Device testing against a local test site + DevTools scripting: see "Test recipes" in docs/PLAN.md.

Gradle's build cache is intentionally off: AGP's resource-merge cache key ignores resource folder
renames and restored an empty `mipmap` output. Lint can also report stale results after
manifest-only edits; rerun it with `lintDebug --rerun-tasks`.

## Conventions
- AGP 9 built-in Kotlin: do NOT apply `org.jetbrains.kotlin.android`. Versions live in `gradle/libs.versions.toml`.
- Nothing user-specific hardcoded (URLs, tokens, PINs) — the app is meant to be distributed.
- Pure logic (URL parsing, schedule windows, allowlists) goes in plain Kotlin files with JVM unit tests under `app/src/test`.
