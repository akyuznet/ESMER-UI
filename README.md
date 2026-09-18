# ESMER·UI

A custom Android launcher for **BYD DiLink 3.0** head units.

The stock launcher gives you a grid and BYD's own bars. ESMER·UI replaces both: a
home screen you arrange yourself, and up to four configurable bars docked to the
edges of the display carrying navigation buttons, climate controls and live
readouts pulled straight off the car's own bus.

Built and tested on a 1920×1080 @ 240dpi DiLink 3.0 unit running Android 10
(SDK 29).

---

## What it does

**Home screen**

- Free placement: any icon anywhere on the grid, gaps included — nothing is packed
- Drag to move, drag past an edge to cross pages
- Android widgets, hosted and resizable by the cell
- Add and remove pages
- An app drawer listing everything on the unit, including the ~85 `MAIN` activities
  with no `LAUNCHER` category that no launcher normally shows (BYD's climate panel,
  radio, camera and diagnostic screens)

**Edge bars**

Up to four bars — top, bottom, left, right — each independently enabled, sized and
populated. They are `TYPE_APPLICATION_OVERLAY` windows, so they persist over other
apps, and can take the system bars' own strip when those are hidden.

Each bar holds any mix of:

- Navigation: Back, Home, Recents, All apps
- Volume up / down / mute
- Climate: temperature and fan steppers, A/C, recirculate, front and rear defrost,
  per-vent airflow, seat heating and ventilation, steering wheel heat
- App shortcuts
- Live readouts (below)

**Live readouts, direct from the car**

Clock, date, gear, road speed, headlight beam, indicators, tyre pressures (bar, psi
or kPa), electric and fuel range with charge and tank percentages, inside and
outside temperature, cabin and outside PM2.5, plus Wi-Fi, cellular and Bluetooth
strength.

Values are read from BYD's automotive bus directly — no dependency on any other
app being installed or running.

---

## How the car is read

BYD's `BYDAutoDeviceManager` exposes a generic feature bus: every signal has an
integer id, read with `getInt`/`getDouble` and written by setting the matching
`_SET` id. Unlike the per-device wrappers (`BYDAutoAcDevice` and friends) it is
**not** gated behind the signature-level `BYDAUTO_*_GET` / `_SET` permissions.

Signal ids live in `BYDAutoFeatureIds$<Group>` and are assigned in the class
initialiser, so they can only be read from a running VM — hence the reflection in
[`BydFeatures`](app/src/main/java/com/example/esmerui/car/BydFeatures.kt).

Two things make this harder than it looks:

**The bus never returns an error.** A signal the car does not carry comes back as an
ordinary integer. Three values mean "nothing here" — `65535`, `-10011` (not fitted)
and `-10013` — and taking one at face value is how a readout ends up claiming
65,535 km of range.

**`-10013` usually means "wrong type".** The bus has a `getDouble` beside its
`getInt`, and asking `getInt` for a double-typed signal returns `-10013`,
indistinguishable from a signal the car genuinely has nothing for. Of 150 signals
that answered that way on this car, 84 answer as doubles — road speed among them.
Probe every new signal with both.

**Encodings are measured, not assumed.** Seat climate counts from one, so `1` means
off. The indicators use a single combined signal where `2` is left and `4` is right,
and it follows the lamp, so it blinks. Gear position lives in
`GEARBOX_AUTO_MODE_TYPE` — a name that reads like a description of the gearbox —
while both signals with "gear" in the name answer "not fitted".

A sweep of every declared signal can be dumped on demand:

```
adb shell am broadcast -a com.example.esmerui.SWEEP -p com.example.esmerui
# ~50 s later
adb pull /sdcard/Android/data/com.example.esmerui/files/bus-scan.txt
```

Any signal can be traced live while you operate the car, which is how the
encodings above were settled:

```
adb shell am broadcast -a com.example.esmerui.WATCH -p com.example.esmerui \
  --es signals Gearbox.GEARBOX_AUTO_MODE_TYPE,Speed.SPEED_AUTO_SPEED \
  --ei hz 5 --ei seconds 60
adb logcat -s BusWatch
```

---

## Building

Requires JDK 17+ (Android Studio's bundled JBR works) and the Android SDK.

```
./gradlew assembleDebug
```

The debug build is shrunk with R8 — tree-shaking only, names and line numbers kept,
still debuggable. That takes the dex from ~29 MB to ~7 MB, which matters on a head
unit where the whole process otherwise sits mapped in memory.

---

## Setting it up on the unit

Install, then grant the pieces that cannot be granted from the UI:

```
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Hide BYD's own navigation and status bars
adb shell pm grant com.example.esmerui android.permission.WRITE_SECURE_SETTINGS

# Host widgets without a confirmation dialog per widget
adb shell appwidget grantbind --package com.example.esmerui --user 0
```

Then, on the unit:

- **Draw over other apps** — required for the bars
- **Accessibility service** — required for Back and Recents

Set it as the default home app from launcher settings, or from the reminder that
appears on the home screen.

> Some units ship another launcher that rewrites the enabled-accessibility-services
> list to just itself, which silently kills Back and Recents. ESMER·UI watches that
> setting and re-asserts itself when it changes.

---

## Notes

- `targetSdk` is 37 but the unit is API 29; the code paths that need it check
  `Build.VERSION.SDK_INT`.
- Overscan (`IWindowManager.setOverscan`, reflected) is used to reserve the screen
  edges so other apps do not draw under the bars. That API exists on Android 10 only.
- Idle cost on the unit is roughly 1% of one core and ~50 MB PSS.

## Licence

[MIT](LICENSE). Do what you like with it; no warranty.

It talks to a car. Read the vehicle notes above before trusting a reading, and
test changes with the vehicle parked.
