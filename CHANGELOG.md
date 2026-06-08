


# [v2.6.0]
### Changes
- Renamed the application to **Hyperion reGrabber** and changed the package id to `com.hyperion.regrabber` (installs as a separate app from the original `com.hyperion.grabber`)
- Reworked screen capture to be event-driven (`ImageReader.OnImageAvailableListener`) instead of a fixed-rate polling loop, throttled to the configured capture rate
- Raised the capture thread from background to display priority so it isn't starved while the device decodes high-bitrate video
- Raised the network send thread to display priority as well
- Reduced the idle keep-alive resend from the full frame rate to a lightweight ~4 Hz heartbeat
- Removed per-frame allocations on the FlatBuffers send path (reused length-prefix buffer, writes the backing array directly) and coalesced the header + payload into a single write per frame
- Black scenes now turn the LEDs **off in realtime** instead of holding the last colour: the idle heartbeat resends the frame that is actually on screen (black included) rather than the last bright frame, so a black movie scene goes dark immediately
- Added a **"Capture Detail"** multiplier setting (`x1`–`x4`, mobile and TV): the screen is grabbed at exactly the horizontal × vertical LED grid, and the multiplier refines it (e.g. `78×40` at `x2` grabs `156×80`) for a smoother result on dense LED setups
- The horizontal/vertical LED counts now drive the captured image size directly (image divided into exactly that many cells) instead of only setting a minimum packet size that an auto-divisor had to clear
- **Raised the minimum Android version to 12 (API 31)** and stripped every pre-Android-12 compatibility branch and annotation for less per-call overhead and a smaller binary
- **Leaner release build**: enabled R8 code minification + resource shrinking, and dropped the now-unnecessary Jetifier, multidex and vector-support-library build flags
- **Average-color mode** now offloads the downscaling to the GPU (renders into a tiny ~32×18 capture surface) instead of averaging a full 128×72 frame on the CPU — far less work for weak TV CPUs
- Removed the dead `WRITE_EXTERNAL_STORAGE` permission (was already capped below the new minimum)

### Fixed
- Heavy frame drops during high-resolution / high-frame-rate playback on Android TV
- LEDs staying lit on the last colour when a black scene arrives — they now follow the screen and go dark
- `isConnected()` no longer reports a closed socket as connected, improving reconnection after sleep/wake

---

# [v2.0.1]
### Changes
- APKs are now signed 

---

# [v2.0]
### Changes
- Full Android 12+ (API 31+) compatibility
- Migrated from Android Support Library to AndroidX
- Updated to Gradle 8.4 and Android Gradle Plugin 8.2.1
- Updated to Kotlin 1.9.22
- Updated target SDK to 34 (Android 14)
- Added foreground service type declaration for media projection
- Added POST_NOTIFICATIONS permission for Android 13+
- Replaced deprecated AsyncTask with ExecutorService/Handler pattern
- Removed ButterKnife dependency (TV app)
- Updated Konfetti library to v2.0.4
- Updated Protobuf to 3.25.1 with protobuf-javalite
- Changed default message priority to 100
- Added proper PendingIntent immutability flags for Android 12+

### Fixed
- FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION error on Android 12+
- PendingIntent mutability crash on Android 12+
- Notification permission handling for Android 13+
- Resource reference issues with AGP 8.x namespace changes

## [v1.0]
### Changes


- OLD
## [v1.0]

- Arabic translation

### Fixed
- Possible NPE when stopping the grabber

## [v0.5-beta]
### Changes
- Added the ability to send only the average color of the screen
- French translation
- Norwegian translation
- Czech translation
- German translation
- Dutch translation
- Partial Russian translation
- Partial Spanish translation
- Removed openGL grabber option
- Added toggle grabber activity shortcut
- LEDs will now be cleared when rebooting or shutting down

### Fixed
- Lights now clear (if running) when shutting down
- Assertion bug in TV settings
- Possible null intent when starting grabber
- OOM bug

## [v0.4-alpha]
### Changes
- Start grabber on device boot
- Added some eye candy for when grabber is started
- General UI tweaks (tv & mobile)
- Reconnect behavior implemented for mobile build
- New connection wizard
- New settings/connection page (tv build)
- Quick settings tile to toggle grabber (mobile build)
- Screen orientation change updates grabber
- Configurable grabber image quality
- Pressing the notification will now return to the app's main activity

### Fixed
- Grabber would fail to resume when waking device
- OpenGL grabber sometimes halting immediately after starting screen grab
- Default grabber failing to send data the first time it is turned on
- Grabber not stopping when the host is unreachable
- Aspect ratio of grabbed image being slightly off
- OOM bug

## [v0.3-alpha]
### Changes
- Leanback launcher support (tv build)
- Revised layout (tv build)
- Reconnect if connection is lost to hyperion server (tv build)

## [v0.2-alpha]
### Changes
- App Icon
- Fancy toggle button
- Bug fixes
- New Grabber (old grabber can be enabled in the settings)

### Known Bugs
- OpenGL grabber will sometimes hang when started, making the lights unresponsive. Quitting the app and starting again generally fixes the problem.
- New grabber fails to send any data the first time it is initialized. Turning off and back on one more time seems to fix the problem.
