# Train Timings widget (VVS Stuttgart)

A home screen widget for Samsung (and any Android 8+) phones that shows the next VVS departures
for the lines and directions you choose. It uses the same `www3.vvs.de/mngvvs` EFA endpoint as the
[ESP32 LCD display](https://github.com/Kajiiiii97/Stuttgart-VVS-Train-Timing-Display), but asks for
JSON so stops can be searched by name.

```
┌──────────────────────┐ ┌──────────────────────┐
│ S1  Herrenberg       │ │ U12 Dürrlewang       │  ← solid pink when it's
│ 4 min      then 19m  │ │ now        then 10m  │    leaving now
├──────────────────────┤ ├──────────────────────┤
│ U14 Vaihingen        │ │ 56  Münster          │
│ 7 min +2   then 17m  │ │ 12 min     then 22m  │
└──────────────────────┘ └──────────────────────┘
                              Updated 13:58 ⟳ ⚙
```

## Install

1. On the phone, open the repo's **Releases** page and download the newest `TrainTimings-N.apk`
   (every push builds one; it's also attached to each Actions run).
2. Open it and allow "Install unknown apps" for your browser when asked.
3. Long-press the home screen → **Widgets** → **Train Timings**, drag it on.
   Or open the Train Timings app, set it up, and tap **Add widget to home screen**.

## Set up

1. Tap **+ Add stop** and search by name (`Pragsattel`, `Hauptbahnhof`) or type a numeric stop ID such
   as `5002260`.
2. Tick the line + direction combinations you care about there, e.g. `U14 → Vaihingen`, and tap
   **Done**. Leave everything unticked to show every line at that stop.
3. Add more stops the same way. One widget can mix lines from any number of stops, like the LCD
   display did. Tap a stop to change its lines, long-press to remove it.
4. Save. Tap the gear on the widget any time to change it.

## How it behaves

- A grid of up to 6 tiles, one per line + direction: a big countdown to the next train and a smaller
  "then X min" for the one after. Countdowns include real-time delays (the red `+2` chip); cancelled
  trips are skipped; trains more than an hour away show their clock time. A tile turns solid pink when its
  train is leaving within a minute. Tiles stay in a fixed line order so they don't jump around.
- Grid size follows the widget size: 2 columns (3 on very wide widgets), up to 3 rows. The default
  4×3 size fits all 6; at 4×2 you get 4.
- Countdowns tick over every minute while the screen is on, using the cached data (no extra downloads).
- Refreshes every 15 minutes, again right after each train leaves, and whenever you tap it.
- Offline it keeps the last data and drops trains that have already left.
- Style: playful geometric, with chunky outlines, solid offset shadows that cycle through the palette,
  pill badges and outline circle/diamond shapes. Colours come from
  the Soft Blush / Powder Blush / Soft Blossom / Cotton Candy / Petal Rouge / Sweet Salmon palette plus
  a near-black plum for outlines and text. All colours are roles in `res/values/colors.xml`, so a new
  theme is one file.

Samsung tip: if the widget stops updating, go to Settings → Battery → Background usage limits and add
Train Timings to **Never sleeping apps**.

## Build locally

Needs JDK 17 and the Android SDK (API 35): `./gradlew assembleRelease`, unit tests with
`./gradlew testDebugUnitTest`.
