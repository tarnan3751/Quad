# Quad

A campus marketplace. Students buy and sell with people on their own campus, from iOS and Android apps built from one Kotlin codebase.

- `.edu` sign-up, listings with photos, search and filters, favorites
- Live chat, ratings, reporting, blocking and a moderation queue
- Payments and seller payouts through Stripe Connect, held until handoff

**Stack:** Kotlin Multiplatform, Compose Multiplatform, Ktor, PostgreSQL, Stripe Connect.

## Run it

Needs JDK 17, Docker and the Android SDK.

```sh
scripts/dev.sh                        # Postgres, demo data and the server on :8080
./gradlew :desktopApp:run             # the app in a desktop window
./gradlew :androidApp:installDebug    # or on a running Android emulator
```

Sign in as `liam@state.edu` (buyer), `maya@state.edu` (seller) or `admin@state.edu`, password `password123`. Without a Stripe key, checkout uses a local fake gateway.

The demo data is two campuses of students with 44 listings, chats, orders, ratings and reports; every login is listed in [`docs/SERVER_NOTES.md`](docs/SERVER_NOTES.md#demo-data-after-gradlew-serverseed). Listing photos are real, freely licensed photographs bundled with the server (credits in [`server/src/main/resources/seed/photos/CREDITS.md`](server/src/main/resources/seed/photos/CREDITS.md)). Re-seed from scratch with `scripts/dev.sh --reset`.

## Test

```sh
./gradlew :server:test :shared:jvmTest
```

## Layout

| Path | What |
|---|---|
| `core/` | Shared wire types and routes |
| `server/` | Ktor API server |
| `shared/` | Compose UI, view models, API client |
| `androidApp/`, `iosApp/`, `desktopApp/` | Platform shells |
| `docs/` | API contract, client notes, release checklist |

The iOS app builds and runs on the simulator: open `iosApp/iosApp.xcodeproj` in Xcode (or see `docs/CLIENT.md` for `xcodebuild` and the UI tests). It has not been run on a device yet. Server settings are documented in [`.env.example`](.env.example).
