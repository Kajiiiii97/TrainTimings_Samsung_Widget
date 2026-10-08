# Train Timings widget (VVS Stuttgart)

A home screen widget for Samsung (and any Android 8+) phones that shows the next VVS departures
for the lines and directions you choose. It uses the same `www3.vvs.de/mngvvs` EFA endpoint as the
[ESP32 LCD display](https://github.com/Kajiiiii97/Stuttgart-VVS-Train-Timing-Display), but asks for
JSON so stops can be searched by name.

```
Stuttgart, Pragsattel            ⟳ ⚙
[U12] Dürrlewang               14:02
[U14] Vaihingen         Pl. 2  14:07 +2
[56 ] Rosensteinbrücke         14:11
                        Updated 13:58
```

## Install

1. On the phone, open the repo's **Releases** page and download the newest `TrainTimings-N.apk`
   (every push builds one; it's also attached to each Actions run).
2. Open it and allow "Install unknown apps" for your browser when asked.
3. Long-press the home screen → **Widgets** → **Train Timings**, drag it on.
   Or open the Train Timings app, set it up, and tap **Add widget to home screen**.

## Set up

1. Search for a stop by name (`Pragsattel`, `Hauptbahnhof`) or type a numeric stop ID such as `5002260`.
2. Tick the line + direction combinations you care about, e.g. `U14 → Vaihingen`.
   Lines that short-turn show up as separate directions, so tick each one you'd take.
   Leave everything unticked to show all departures at the stop.
3. Save. Tap the gear on the widget any time to change it. Add several widgets for different stops.

## How it behaves

- Shows clock times with real-time delays (`+2` in red); cancelled trips are struck through.
- Resize the widget taller to show more departures.
- Refreshes every 15 minutes, again right after each train leaves, and whenever you tap it.
- Offline it keeps the last data and drops trains that have already left.
- Follows your One UI colour palette and dark mode on Android 12+.

Samsung tip: if the widget stops updating, go to Settings → Battery → Background usage limits and add
Train Timings to **Never sleeping apps**.

## Build locally

Needs JDK 17 and the Android SDK (API 35): `./gradlew assembleRelease`, unit tests with
`./gradlew testDebugUnitTest`.
