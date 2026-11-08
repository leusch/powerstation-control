# Third-party notices

Powerstation Control is an **unofficial** controller for portable power stations that speak the BrightEMS Bluetooth protocol
(tested on an AFERIY P180). It is not affiliated with, endorsed by, or supported by AFERIY, BrightEMS,
Fossibot or SYDPOWER. All product names and trademarks belong to their respective owners.

## Icons (bundled)

The UI icons in `app/src/main/res/drawable/ic_*.xml` (except the `ic_launcher_*` files) are converted from the
**Material Icons** (Outlined) by Google, licensed under the **Apache License 2.0**
(full text: `LICENSES/Apache-2.0.txt`). Source: <https://github.com/google/material-design-icons>,
obtained through the npm package `@material-design-icons/svg` 0.14.13 (<https://github.com/marella/material-design-icons>,
Apache-2.0). They were converted from SVG to Android vector drawables without changing the shapes.

Used icons: arrow_back, arrow_downward, arrow_upward, battery_charging_full, battery_full, directions_car,
electrical_services, hourglass_empty, settings, usb, warning.

## App icon (bundled)

`ic_launcher_*` and `design/app-icon.svg` (battery with a lightning bolt) are original artwork created for this
project and are covered by the project's own license.

## Design

Colours, radii and type sizes follow the author's own "Zora" design tokens.

## Protocol research (no code copied)

The Bluetooth frame format and register meanings were derived from captures of the official app on the author's
own unit, cross-checked against these public projects. Only protocol facts (UUIDs, register numbers, frame
layout) were used; no source code was copied:

- ESP-FBot by Ylianst (Apache-2.0): <https://github.com/Ylianst/ESP-FBot>
- aferiy-p180-esphome by Andriy Mukan (MIT): <https://github.com/amukan/aferiy-p180-esphome>
- kraftverk by Olof Dahlbom (MIT), for safety notes on the sibling P280: <https://github.com/olofd/kraftverk>

## Build dependencies (downloaded by Gradle, not bundled in this repository)

AndroidX, Jetpack Compose, Kotlin and kotlinx-coroutines: Apache License 2.0.
JUnit 4 (tests only): Eclipse Public License 1.0.
