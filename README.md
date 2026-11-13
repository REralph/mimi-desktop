# Mimi — desktop

![Kotlin](https://img.shields.io/badge/Kotlin-2.4-7F52FF?logo=kotlin&logoColor=white)
![Compose Multiplatform](https://img.shields.io/badge/Compose-Desktop-4285F4?logo=jetpackcompose&logoColor=white)
![Windows](https://img.shields.io/badge/platform-Windows-0078D6?logo=windows&logoColor=white)
![Tests](https://img.shields.io/badge/tests-833%20passing-brightgreen)
![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)

**Mimi** is a personal life-organiser made of two apps I designed and built: an Android app (habits,
calendar, tasks, gym log, dream journal) and this **Windows desktop companion**, which syncs with the
phone over home Wi-Fi and adds a big-screen calendar, a skin-care and supplements planner, and a
job-application tracker that reads Gmail.

![Calendar page](docs/screenshots/calendar.png)

| Gym history | Job tracker (Gmail) |
|---|---|
| ![Gym page](docs/screenshots/gym.png) | ![Jobs page](docs/screenshots/jobs.png) |

*Screenshots are rendered from the app's built-in sample data (`gradlew renderPreviews`), not real records.*

## Try it (Windows 10/11, no install)

1. Download `Mimi-desktop-1.0.1-windows-x64.zip` from the [latest release](https://github.com/REralph/mimi-desktop/releases/latest)
   and unzip it. Java is included.
2. Double-click **`Start Mimi demo.bat`**. The app opens with invented sample data and a pretend phone,
   and nothing is read from or saved to your PC.

Windows may warn that the app isn't code-signed: click **More info** → **Run anyway**. `Mimi.exe` on its own
is the real app, which pairs with the Mimi phone app over Wi-Fi.

## Highlights

- **Phone ↔ PC sync with its own secure protocol.** The phone runs a small HTTP server and the PC
  polls it. Requests are signed with **HMAC-SHA256** (timestamp + nonce, so they can't be replayed),
  and responses are gzipped and encrypted with **AES-256-GCM** using keys derived from a 100-bit
  pairing key. Pairing is a **QR code** scan, and the PC finds the phone on the network by itself.
- **The phone is the source of truth.** Edits made on the PC (habits, events, tasks, dreams,
  skin-care choices) are sent as commands the phone validates and saves. Edits made while the phone
  is unreachable wait in an **offline outbox**, and timestamps settle conflicts (the later change wins).
- **Gmail integration (read-only OAuth)** that classifies application emails (applied, interview,
  offer, rejection) and merges them with job folders on disk into one status table.
- **Rules-based planners**: an evening skin-care plan that spaces out exfoliating nights and can use
  a UV forecast (Open-Meteo), and a supplements schedule checked against NHS/EFSA upper limits.
- **Well tested**: 833 automated tests (unit, UI and **interop tests** that compile the phone's real
  link code and run the desktop client against it, so the two sides can't drift apart).
- Custom dark "glass" UI in Compose Desktop with its own window frame, an emoji picker and a
  developer mode that labels every UI section with a reference number.

## Tech stack

Kotlin 2.4 · Compose Multiplatform (Desktop) · Material 3 · kotlinx.serialization · Java HTTP client ·
javax.crypto (AES-GCM, HMAC) · ZXing (QR codes) · Gmail REST API with OAuth 2.0 · Gradle · jpackage (MSI)

The Android half (Kotlin, Jetpack Compose, Room, WorkManager) lives in a separate private repository
because it holds personal data.

## Build and run

Requires JDK 17+ on Windows.

```bash
gradlew run                 # start the app
gradlew run --args=--demo   # start it with sample data and a pretend phone (see DemoMode.kt)
gradlew test                # run the test suite
gradlew renderPreviews      # render every page to PNG with sample data (build/previews)
gradlew createDistributable # self-contained app folder with Mimi.exe (build/compose/binaries)
install.bat                 # build and install as "Mimi" with Desktop and Start-menu shortcuts
```

Without a paired phone the real app shows only the **Phone** page. Everything else needs a phone running
the Mimi Android app on the same Wi-Fi, or the demo mode.

## More

- **[User guide](docs/USER_GUIDE.md)**: every page and setting in detail.
- The Android half of Mimi (Kotlin, Jetpack Compose, Room) is a separate private repository.

## Licence

[MIT](LICENSE) © 2026 Ralph Estrocio
