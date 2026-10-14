# Train Timings widget (VVS Stuttgart)

A home screen widget for Samsung (and any Android 8+) phones that shows the next VVS departures
for the lines and directions you choose. It uses the same `www3.vvs.de/mngvvs` EFA endpoint as the
[ESP32 LCD display](https://github.com/Kajiiiii97/Stuttgart-VVS-Train-Timing-Display), but asks for
JSON so stops can be searched by name.

```
Stuttgart Hauptbahnhof                    ⟳ ⚙
┌──────────────────────┐ ┌──────────────────────┐
│ S1  Herrenberg       │ │ U12 Dürrlewang       │  ← orange when it's
│ 4 min    then 19 min │ │ now      then 10 min │    leaving now
├──────────────────────┤ ├──────────────────────┤
│ U14 Vaihingen        │ │ U14 Mühlhausen       │
│ 7 min +2 then 17 min │ │ 12 min   then 22 min │
└──────────────────────┘ └──────────────────────┘
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

- A grid of up to 6 tiles, one per line + direction: a big countdown to the next train and a smaller
  "then X min" for the one after. Countdowns include real-time delays (the red `+2` chip); cancelled
  trips are skipped; trains more than an hour away show their clock time. A tile turns orange when its
  train is leaving within a minute. Tiles stay in a fixed line order so they don't jump around.
- Grid size follows the widget size: 2 columns (3 on very wide widgets), up to 3 rows. The default
  4×3 size fits all 6; at 4×2 you get 4.
- Countdowns tick over every minute while the screen is on, using the cached data (no extra downloads).
- Refreshes every 15 minutes, again right after each train leaves, and whenever you tap it.
- Offline it keeps the last data and drops trains that have already left.
- Colours: Ink Black / Deep Navy background, Egyptian → Twitter Blue tiles, Jasmine accents,
  Harvest Orange for trains leaving now, Cayenne Red for delays.

Samsung tip: if the widget stops updating, go to Settings → Battery → Background usage limits and add
Train Timings to **Never sleeping apps**.

## Build locally

Needs JDK 17 and the Android SDK (API 35): `./gradlew assembleRelease`, unit tests with
`./gradlew testDebugUnitTest`.
