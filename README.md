# Cup 'n Log ☕

A free, open-source Android app that guides you through a **coffee cupping session**, step by step, with timers.

## What it does

### Set up and run a session

- Start a timer immediately, or set up a cupping with 1–16 coffees and a cup
  size. CofCup calculates the coffee amount from the SCA ratio of 8.25 g per
  150 ml.
- Give coffees names, numbers, and colors, or make the session blind. Setups
  can be saved for later; completed sessions appear in History.
- Follow the guided timeline for pouring, breaking the crust, cleaning the
  cups, and tasting. The timer starts at 0:00 when water first hits the
  grounds. Chimes and vibration can be configured in Settings.

### Rate and share

- Rate each cup's aroma, flavor, acidity, sweetness, mouthfeel, and aftertaste
  from 1–10. Overall is a separate manual score; tasting notes are optional.
- In a blind session, coffees stay identified by cup position until the
  reveal. For group cuppings, share a setup by QR code or app link, collect
  participants' ratings over Nearby Connections or QR, then share the group
  results. Imports and merges require confirmation.
- Browse planned setups and completed sessions in History. Review ratings and
  notes or remove sessions you no longer need.

## The cupping timeline used (0:00 = first water hits the grounds)

| Time | Step |
|---|---|
| 0:00 → 4:00 | Fill the cups and let steep (crust forms, don't touch) |
| 4:00 → 6:00 | Break the crust (3 pushes with a spoon, smell the aromas), then grounds settle |
| 6:00 → 8:00 | Clean the top of the cup (skim foam & floaters, then cool to ~70 °C) |
| 8:00 → 15:00 | First cupping round (slurp; flavour, acidity, body) |
| 15:00 → 22:00 | Second cupping round (taste as cups cool; sweetness opens up) |
| 22:00 → 30:00 | Optional third cupping round (near room temperature; optional) |
| Done | Cupping complete — identify the coffees |

Timers follow the [SCA cupping protocol](https://sca.coffee/research/cupping-protocol): water at ~93 °C, a 4-minute undisturbed steep, the crust broken at the 4-minute mark, tasting starting ~8 min after the pour at ~70 °C, and further passes as the cups cool. See also [Gota's summary of the SCA protocol](https://gota.cafe/en/learn/cupping/sca-protocol) and [Conscious Cup's step table](https://consciouscup.com/pages/brew-methods/cupping).

## Tech stack

- Kotlin + **Jetpack Compose** (Material 3)
- Jetpack ViewModel + coroutines for the timers
- AndroidX DataStore (JSON payload) for the session history — no external serialization plugin needed
- No backend: sessions stay on-device unless you choose to share them directly
  with participants. Nearby Connections supports wireless ratings exchange;
  QR codes and app links provide a fallback.
- **AGP 9.5** with built-in Kotlin support (no `kotlin-android` plugin), Gradle 9.6, `compileSdk 37`, `minSdk 26`

## Building

Requirements: **JDK 17**, an **Android SDK (API 37)** and a recent **Android Studio** supporting AGP 9.5 (Android Studio 2026.2.2 "Rabbit 2" or newer).

1. Open this folder in Android Studio → let it sync (it downloads Gradle 9.6 via `gradle/wrapper/gradle-wrapper.properties`).
2. Run the `app` configuration on a device/emulator.

From the command line (if you have Gradle 9.6 and the SDK configured):

```bash
gradle :app:assembleDebug
gradle :app:testDebugUnitTest   # runs the ratio/timeline maths unit tests
```

> The gradle wrapper (`gradlew` + `gradle/wrapper/*`) **is committed** — run `./gradlew :app:assembleDebug` (Windows: `gradlew.bat`). It also keeps CI and the F-Droid buildserver on the same Gradle 9.6.0.

## Roadmap

- Optional pre-cupping preparation before the timer, including dry-aroma
  rating and a blind-tasting cup shuffle. Design how preparation is entered,
  skipped, and saved, and whether dry aroma uses the existing Aroma score.
- An About section in Settings with the app version and build number, so
  participants can compare builds during development.
- Show the upcoming step's instruction in the timer screen's bottom note;
  decide what to show at the final step and after skipping a step.
- Screen to freely walk the timeline while keeping the session alive mid-cupping (wizard re-entry)
- Localisation (English UI today)

## Contributing

Issues and PRs are welcome — the codebase is intentionally small: one module (`:app`), a small data layer (`data/`), and one file per screen (`ui/`). The cupping ratio/timeline maths lives in `data/CuppingMath.kt` / `data/CuppingTimeline.kt` and is unit-tested.

## License

[MIT](LICENSE) — © 2026 Arthur
