# Compose and Material 3 interface

Roboff's app screens are written in Kotlin with Jetpack Compose. Material 3 Compose components follow the Material component guidance and expressive direction through prominent section headings, rounded tonal surfaces, large primary actions, discrete sliders, and short navigation transitions.

## System appearance

`RoboffTheme` uses Android's dynamic light or dark color scheme on Android 12 and newer. It follows the device's current theme and accent palette without an in-app color override. Older Android versions use paired fallback color schemes. Rounded Material shapes are applied to cards and other surfaces.

The manifest theme supplies the Activity window defaults. Compose controls and surfaces read from `MaterialTheme.colorScheme`, including the system accent and surface roles. The launcher artwork remains fixed.

## Refresh and motion

Status polling runs every eight seconds on a worker thread. It updates Compose state in place: the screen keeps its current tab and scroll position, and the app does not replace the main content during an API refresh. A small indicator appears in the connection row while a read is active. The robot's reported cleaning progress animates to its latest value.

`AnimatedContent` is limited to top-level tab changes. It does not wrap telemetry, room lists, or care values, so ordinary LAN updates do not replay a page transition. Compose animations follow the system animation scale.

## Rooms

The Rooms screen reads `get_room_mapping` directly from the vacuum. It shows imported room names when their cloud identifiers match; otherwise it uses a numeric fallback. A local name override can be edited without a cloud request. Select all, Clear, a one-to-three pass choice, and the clean action are available with the loaded room list.

The confirmation names the selected rooms and pass count. The app validates that the same vacuum and room IDs are still available before sending `app_segment_clean`, which contains only the confirmed room IDs. Failed refreshes keep previously loaded rooms and selections on screen.

## Cleaning preferences and progress

The preference controls use Compose Material 3 discrete `Slider` components. Their stops show supported labels instead of protocol values. A drag previews locally and sends one setting command when the user finishes adjusting it. Status updates do not overwrite an active drag.

The robot illustration is drawn with Compose Canvas. A Material circular progress indicator surrounds it and animates between reported `clean_percent` values while cleaning. Missing progress remains indeterminate or empty according to connection state; the app does not estimate completion from battery, time, or cleaned area.

## Onboarding

Onboarding uses Compose text fields, buttons, cards, progress indicators, and transitions between sign-in, code entry, retry, and vacuum selection. Account access occurs only in this setup Activity. Secret inputs are masked and the window is protected from screenshots until the catalog is displayed. The session survives Activity recreation while the owner switches to an email app to retrieve a code.

## References

- [Material components](https://m3.material.io/components)
- [Building with M3 Expressive](https://m3.material.io/blog/building-with-m3-expressive)
- [Android Material components](https://m3.material.io/develop/android/mdc-android)
- [Slider behavior](https://github.com/material-components/material-components-android/blob/master/docs/components/Slider.md)
- [Progress indicator guidance](https://m3.material.io/components/progress-indicators/overview)
