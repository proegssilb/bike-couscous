# bike-couscous

An Android app that reads live sensor data from a Reebok SL8.0 exercise bike
over BLE and writes each finished ride into Android Health Connect (the store
Trainwell and friends read from).

It's a straight port of the protocol reverse-engineered in `reebok_sl8.py`
(BM70/ISSC Transparent UART, Chang Yow framing/handshake) onto Android's own
BLE stack, plus a recorder and a Health Connect writer.

## What it does

1. Connects directly to the bike by BLE MAC address (default
   `E8:5D:86:BF:D4:C9`, editable in the app) — no scan needed, same as the
   original script.
2. Runs the Chang Yow init handshake and keep-alive noop loop, reconnecting
   automatically if the bike drops out.
3. Parses the 26-byte data notifications into speed, cadence, resistance,
   calories, distance, heart rate, and run/stop state.
4. While a workout is "started", buffers those samples; on "stop", inserts
   an `ExerciseSessionRecord` (stationary biking) into Health Connect along
   with heart rate, speed, and cadence time series, plus total distance and
   calories for the ride.
5. Runs the BLE connection in a foreground service so it survives the screen
   turning off mid-ride.

## Project layout

- `ble/` — protocol constants, packet parsing, the `ReebokBleClient` GATT
  client (handshake, reconnect, notification reassembly).
- `recorder/` — turns the live sample stream into a bounded workout session.
- `health/` — `HealthConnectRepository`, permission handling, record building.
- `service/` — `RecordingService`, the foreground service tying BLE + the
  recorder + Health Connect together and driving the status notification.
- `ui/` — the single-screen Compose UI.

## Building

This was written without access to the Android SDK or a device, so it has
**not been compiled**. Everything is standard, current-generation
Kotlin/Compose/Health Connect code, checked carefully against the real API
signatures, but you should expect to fix at least minor build hiccups.

`gradle/wrapper/gradle-wrapper.jar` (a binary) couldn't be pushed through this
environment's GitHub access, so after cloning, regenerate it once with a
locally installed Gradle:
```
gradle wrapper --gradle-version 8.9
```
Android Studio will also offer to do this automatically the first time you
open the project if you skip that step.

1. Open the project root in Android Studio (Ladybug or newer) and let it
   sync — it'll fetch the Android SDK bits and Gradle dependencies for you.
2. Confirm your bike's BLE MAC address if it isn't `E8:5D:86:BF:D4:C9`.
3. Build & run onto your Pixel over USB (`adb devices` should show it once
   USB debugging is on, which you said is already enabled):
   ```
   ./gradlew installDebug
   ```
   or just hit Run in Android Studio.
4. On first launch, grant Bluetooth, Notifications, and Health Connect
   permissions from the in-app "Setup" card, then hit **Connect to bike**.
5. Once it says "Connected", **Start workout**, ride, then **Stop & save**.
   The ride shows up in Health Connect (and anything reading from it, like
   Trainwell) immediately.

No Play Store listing, no signing config beyond the debug key — this is
meant to be sideloaded onto your own phone.
