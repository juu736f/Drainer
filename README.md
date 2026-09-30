# Drainer

Android battery diagnostic tool. One toggle starts every major power consumer at maximum rate and keeps them running in the background. Used to measure discharge rate, thermal behavior, and battery health under worst-case load.

## Warnings

- Expect heavy heat and fast discharge. Run on a hard surface, out of a case, not in a pocket.
- Sustained full load accelerates battery wear. Do not run unattended on a swollen, damaged, or overheating battery.
- Camera, microphone, and location privacy indicators will show. This is expected.
- Nothing is recorded, stored, or transmitted. Camera frames and audio samples are discarded immediately. The app has no `INTERNET` permission.

## Requirements

| Item | Version |
|---|---|
| Device | Android 12 (API 31) or newer |
| JDK | 17 |
| Android SDK | Platform 34 |
| Gradle | 8.7 or newer (AGP 8.5.2) |
| Kotlin | 1.9.24 |

## Build and install

Android Studio: open the `Drainer` folder, sync, run.

Command line:

```
gradle wrapper --gradle-version 8.7
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Use

1. Launch Drainer and flip the switch.
2. Grant camera, microphone, location, Bluetooth, and notification permissions. Phone state is optional and is used for cell scanning. Camera, microphone, location, and Bluetooth are all required or the toggle reverts to off.
3. Enable location services and Bluetooth in system settings. Wi-Fi scans need Wi-Fi on.
4. Leave the app. The foreground service continues with a persistent notification.
5. Flip the switch off, or return to the app and toggle it, to stop everything.

Compare battery percentage and temperature over a fixed time window against a baseline, or read `adb shell dumpsys batterystats`.

## What runs when toggled on

| Load | Implementation |
|---|---|
| CPU | One thread per core, trial-division prime search over odd numbers. Count and last prime shown. |
| Vibration | Continuous waveform at amplitude 255, alarm usage attributes so it is not suppressed in the background. |
| Camera | Back camera, largest YUV_420_888 size up to 1920 px wide, streamed into an ImageReader that drops every frame. |
| Flashlight | `FLASH_MODE_TORCH` in the camera repeating request. Requires a device with a flash unit. |
| Microphone | AudioRecord, 44.1 kHz mono PCM16, read and discarded. |
| Location | GPS, network, and fused providers at 0 ms / 0 m. Latitude, longitude, and accuracy shown. |
| Wi-Fi | `startScan()` every second and again on each scan-result broadcast. AP count shown. |
| Bluetooth | Low-latency BLE scan plus classic discovery, restarted when it finishes. Unique device count shown. |
| Cellular | `requestCellInfoUpdate` re-requested 100 ms after each response. Tower count shown. |
| Display | Window brightness 1.0, keep-screen-on, status and navigation bars hidden (swipe from an edge to reveal temporarily). |
| CPU wake | Partial wake lock held for the life of the service. |
| Screensaver | After 300 s without touch: 40 fast bouncing balls over faint animated noise. Any touch dismisses it. |
| Burn-in | Main screen text block shifts by up to 40 px every 60 s. |

Each subsystem starts independently. A failure in one (for example, no flash unit) does not stop the others.

## Limitations

- Android throttles Wi-Fi scans to 4 per 2 minutes in the foreground and 1 per 30 minutes in the background. Disable Developer Options > Wi-Fi scan throttling to remove the limit.
- Forcing a full modem network scan (`requestNetworkScan`) is restricted to system and carrier-privileged apps. The modem or OS may cache or throttle cell info responses. Devices without a SIM return errors and the loop keeps retrying.
- Brightness 1.0 applies to the app window only. It does not hold once another app is in front or the screen turns off.
- Thermal throttling reduces CPU, radio, and camera throughput as the device heats up. Sustained load will decline over time.
- Another app holding the camera or microphone prevents those loads from starting.

## Layout

```
settings.gradle.kts
build.gradle.kts
gradle.properties
app/build.gradle.kts
app/src/main/AndroidManifest.xml
app/src/main/java/dev/drain/DrainService.kt   shared State object, foreground service, all loads
app/src/main/java/dev/drain/MainActivity.kt   toggle UI, permissions, immersive mode, BallsView screensaver
```

Application ID: `com.kekonen.drainer`. 

## Permissions

`CAMERA`, `RECORD_AUDIO`, `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`, `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `READ_PHONE_STATE`, `VIBRATE`, `WAKE_LOCK`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CAMERA`, `FOREGROUND_SERVICE_MICROPHONE`, `FOREGROUND_SERVICE_LOCATION`.

The service declares foreground service types `camera|microphone|location`.
