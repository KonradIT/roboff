# Roboff

Roboff is an Android unofficial client for direct, offline control of Roborock vacuums. No MQTT cloud connection, no dependency on Roborock's server besides login/activation. Robot can be firewalled off of the internet by using this app.

Tested exclusively with the Roborock S8 (`a51`) model.

## Motivation

I've been using my [ESP32-based roborock remote](https://github.com/KonradIT/roborock-remote) in order to interface with the robot for some time now. 

An application on my phone was the natural next step. 

The Roborock S8 isn't easily rootable, requiring convoluted steps by prying off the motherboard and using a Linux box. Therefore installing software which removes cloud connectivity with Roborock's server, something such as Valetudo, is essentially not feasible for most users.

The python-roborock project maintains a [local MQTT server for roborock devices](https://github.com/Python-roborock/local_roborock_server) which can be self hosted but requires a server to run (and the ability to reroute DNS queries).

The middle ground is this.

This app doesn't connect to the roborock cloud for operating the robot. Cloud connectivity is only required in the login process. All other operations are performed by hitting the robot's local IP address.

You can then block all outgoing internet connection from the robot in your router.

## Connect

1. During setup, sign in with your Roborock password or request an emailed sign-in code. Email code works best in my usage.
2. Choose which discovered vacuums to save. Setup retrieves local keys and home/room names, then encrypts the imported profiles with Android Keystore.
3. **Put the phone and vacuum on the same Wi-Fi network.** The app discovers vacuums over mDNS and LAN broadcasts, with a bounded local greeting scan as a fallback.

After setup, discovery, status, care information, rooms, and commands use the direct local protocol. Manual pairing with an existing 16-byte local key is also available. See [onboarding and offline authentication](docs/OFFLINE_AUTH.md).

## Features

- **Status query**: Live battery, state, errors, cleaned area, duration, and animated cleaning progress.
- **Control**: Start selected-room cleaning using a predefined mode just like the official app: Vac & Mop, Vac, Mop. Pause + resume paused room cleaning, return to dock, and find vacuum.
- Fine grained controls for suction, mop intensity, and mop route, if the presets don't cut it.
- Remote control via touchscreen input or BLE joystick (PlayStation remote).
- Maintenance counters and lifetime cleaning totals from the vacuum.
- Advanced settings such as Do Not Disturb hours, child lock, voice volume, and carpet boost.
- Multiple locally saved vacuums, encrypted key editing, and profile removal.

The cloud client is limited to account authentication and setup metadata over HTTPS. **There is no MQTT connection, cloud vacuum control, cloud fallback, analytics, or remote asset loading. All controls happen by local IP address**. Therefore house maps remain out of scope because the supplied S8 reference uses MQTT for map data.

## Android interface

The screens use Compose Material Design 3 components, dynamic color schemes, rounded tonal surfaces, large section headings, accessible sliders, and animated progress. Room and care refreshes update the existing screen instead of rebuilding it. See [UI behavior](docs/UI.md).

## Build and install

The project uses the installed Android SDK 36 toolchain, Android Gradle Plugin 8.13.2, Gradle 8.14.5, Kotlin, and Java 17 bytecode targeting. No NDK is required.

Set the Android SDK path in an untracked `local.properties`, or provide `ANDROID_HOME`:

```properties
sdk.dir=C\:/Users/konra/AppData/Local/Android/Sdk
```

```powershell
.\gradlew.bat :app:assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n com.roboff.lan/.MainActivity
```

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. Current build and device verification is recorded in [TESTING.md](docs/TESTING.md).

## References

Many thanks to the [python-roborock](https://github.com/Python-roborock/python-roborock) project.

See [protocol notes](docs/PROTOCOL.md) and [offline authentication](docs/OFFLINE_AUTH.md).
