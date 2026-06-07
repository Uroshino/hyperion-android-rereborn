# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

An Android screen grabber for [Hyperion](https://hyperion-project.org/) ambient lighting. It captures
the device screen via `MediaProjection` and streams it (as scaled-down image data or as a single
average color) to a Hyperion server, or directly to a WLED controller. A **single APK** targets both
**phones** and **Android TV** (Leanback). Reborn = modernized for Android 12+ (API 31+).

## Build / test commands

Use the Gradle wrapper (Gradle 8.10.2, AGP 8.2.1, Kotlin 1.9.22, **JDK 17 required**):

```bash
./gradlew assembleDebug          # build debug APKs (app + common)
./gradlew assembleRelease        # release build (note: minifyEnabled false)
./gradlew :app:assembleDebug     # build only the app module
./gradlew test                   # JVM unit tests (junit4)
./gradlew :app:testDebugUnitTest # unit tests for one module/variant
./gradlew connectedAndroidTest   # instrumented tests (needs device/emulator)
./gradlew lint                   # Android lint
./gradlew clean
```

An Android SDK is required: set `ANDROID_HOME` or create `local.properties` with `sdk.dir=...`
(not committed). Test coverage is currently minimal (only a placeholder `ExampleUnitTest`).

## Module layout

Two Gradle modules (`settings.gradle`):

- **`:common`** (`com.android.library`, namespace `com.hyperion.grabber.common`) — the entire grabber
  engine: capture service, screen encoder, networking, preferences. This is where almost all real
  logic lives.
- **`:app`** (`com.android.application`, namespace `com.hyperion.grabber`, applicationId
  `com.hyperion.grabber`) — UI only. Depends on `:common` via `api project(':common')`.

### Package vs. directory gotcha (important)

In `:common`, the on-disk directory is `common/src/main/java/com/hyperion/grabber/...` but the
declared package is **`com.hyperion.grabber.common`** (the directory does not contain a `common/`
segment). When referencing these classes from `:app` or the manifest, use the fully-qualified
`com.hyperion.grabber.common.*` name (e.g. `com.hyperion.grabber.common.HyperionScreenService`),
**not** the directory path. The `hyperionnet` package is the exception — directory and package match.

## Architecture: the capture → stream pipeline

The core data flow, all driven from `:common`:

```
MediaProjection (user-granted screen capture)
   → HyperionScreenService          foreground service (type mediaProjection), owns lifecycle
       → HyperionScreenEncoder      ImageReader/VirtualDisplay; scales frames, optional avg-color,
         (extends HyperionScreenEncoderBase)   border detection (BorderProcessor)
           → HyperionThread         background Thread + single-thread ExecutorService;
                                     coalesces frames (drops stale ones), handles reconnect
               → HyperionClient     interface; one of:
                   • HyperionFlatBuffers — TCP to Hyperion, FlatBuffers protocol, tcpNoDelay
                   • WledDdpClient       — UDP (DDP) directly to a WLED instance
```

Key points when modifying this path:

- **`HyperionScreenService`** is controlled by `Intent` actions (`ACTION_START` / `ACTION_STOP` /
  `ACTION_EXIT` / `GET_STATUS`, all prefixed `com.hyperion.grabber.service.`) and reports status/errors
  back to UI via `LocalBroadcastManager` (`BROADCAST_FILTER`, `BROADCAST_TAG`, `BROADCAST_ERROR`).
  The `MediaProjection` result code/intent are passed in as extras. It holds a `WakeLock` and pauses
  on screen-off.
- **`HyperionThreadListener.sendFrame`** is the hot path: it stores the latest frame and cancels any
  in-flight send, so only the newest frame is transmitted — don't add blocking work here.
- **`HyperionClient`** is the abstraction that lets the same encoder output drive either Hyperion
  (image data) or WLED (DDP pixel packets). WLED's `clear/setColor` methods are mostly placeholders;
  it primarily handles `setImage`.
- The **`hyperionnet` package is generated FlatBuffers code** (Hyperion's flatbuffer schema). Treat it
  as generated — do not hand-edit; regenerate from the schema if the protocol changes.

## Phone vs. TV UI (`:app`)

One application, two launcher entry points declared in `app/src/main/AndroidManifest.xml`:

- **Mobile**: `com.hyperion.grabber.MainActivity` (LAUNCHER) + `SettingsActivity`
  (`AppCompatPreferenceActivity` / androidx preferences). Also exposes a **Quick Settings tile**
  (`HyperionGrabberTileService`) and a transparent **`ToggleActivity`** launcher shortcut to
  start/stop the grabber.
- **Android TV**: `com.hyperion.grabber.tv.activities.MainActivity` (LEANBACK_LAUNCHER) plus a
  GuidedStep-based setup wizard under `tv/` — `NetworkScanActivity` → `ScanResultActivity` /
  `ManualSetupActivity`, with `tv/fragments/settings/*StepFragment`.
- **Boot autostart**: `HyperionGrabberBootReceiver` (BOOT_COMPLETED) launches the grabber via
  `BootActivity` when "Grab on Boot" is enabled.

## Configuration and preferences

- **`Preferences`** (`util/Preferences.kt`) wraps `SharedPreferences` and is keyed by **String
  resource IDs**, with defaults sourced from resources (string/integer/bool). Add new settings by
  defining the key + default in `res/values/` and reading through this class, rather than using raw
  string keys.
- **`HyperionGrabberOptions`** carries runtime capture config (frame rate, LED counts, scaling
  divisor, avg-color, black threshold) from preferences into the encoder.

## Localization

UI strings live in `common/src/main/res/values*/strings.xml` and `app/src/main/res/values*/strings.xml`
across ~9 locales (ar, cs, de, es, fr, it, nl, no + default). The user-facing app name is the
`app_name` string (plus `toggle_activity_label`, `quick_tile_label`, `notification_channel_label`).
`translate.sh` is a one-off helper for bulk-appending a new string key across all translation files —
not part of the build.

## SDK / compatibility constraints

`minSdk 21`, `targetSdk 34`, `compileSdk 34`. Much of the service code branches on `Build.VERSION`
for Android 12+/13+ requirements: `FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION`, `POST_NOTIFICATIONS`,
and immutable `PendingIntent` flags. Preserve these guards when touching service, notification, or
permission code (see `PermissionHelper`). `android.nonTransitiveRClass=true` is set, so reference R
classes from the correct module's namespace.
