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

The iOS app has not been built or run yet. Server settings are documented in [`.env.example`](.env.example).
