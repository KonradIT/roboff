# Build and device verification

## Kotlin and Compose rewrite

On 27 October 2026, the following host checks passed:

- `:app:compileDebugKotlin`
- `:app:assembleDebug`
- `:app:compileDebugJavaWithJavac` reports `NO-SOURCE`; there are no `.java` files under `app/src`.

The resulting debug APK was installed on the ADB-connected Pixel 10 (`56101FDCR00FVF`) and launched. It opened the saved Roborock S8 profile, connected directly over Wi-Fi, and displayed live status, cleaning controls, preference sliders, and the Control/Rooms/Care navigation. The Rooms screen loaded four imported room names, and the Care screen displayed live maintenance and lifetime counters. The sign-in screen also opened through **Devices → Import from Roborock account** without requesting a code. Logcat showed no app crash. No vacuum command was sent during this check; all vacuum requests were read-only.

The previous Java unit tests and Views-based instrumentation suite are not in the active source set after this conversion. Automated Compose tests and a live onboarding-code round trip have not been run in this pass.

## Repeat the device smoke check

Build and install the latest debug APK, then launch the normal entry point:

```powershell
.\gradlew.bat :app:assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n com.roboff.lan/.MainActivity
```

Opening the main Activity with a previously paired device performs a local `get_status` read. It does not start, pause, stop, dock, or locate the vacuum.

To inspect the sign-in screen without requesting an email code, open **Devices → Import from Roborock account**.

Requesting a code, signing in, and importing a catalog contact Roborock's HTTPS account service by design. Vacuum control remains local.

## Earlier S8 observations

The earlier Android Views build was tested on the physical Pixel 10 with the Roborock S8. The owner completed email-code sign-in, imported the vacuum, and confirmed live local status and imported room names. That run established the LAN protocol and cloud bootstrap behavior. It predates the Compose rewrite, so those UI observations do not count as Compose regression coverage.
