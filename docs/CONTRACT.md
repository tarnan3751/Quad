# Quad — engineering contract

Campus marketplace: students buy and sell from iOS and Android apps built from one Kotlin codebase.
Listings with photos, search, chat, ratings, reporting and moderation, payouts through Stripe Connect.

This document plus `core/` (the wire types) is the contract every piece of work builds against.
If something here is wrong or missing, make the smallest sensible decision, and say so in your final report.

## Modules

| Path | What | Notes |
|---|---|---|
| `core/` | KMP library (jvm, android, ios): DTOs `Models.kt`, paths + limits `ApiRoutes.kt` | The wire contract. Additive changes only, and flag them. |
| `server/` | Ktor 3 (Netty) JVM server, PostgreSQL via plain JDBC + HikariCP | Package `com.campusmarket.server` |
| `shared/` | KMP + Compose Multiplatform app code (android, ios, jvm desktop): UI, view models, API client | Package `com.campusmarket.app`; iOS framework `Shared` |
| `androidApp/` | Android application shell | `com.campusmarket.app` |
| `iosApp/` | Xcode project shell (SwiftUI host for `MainViewController()`) | No Xcode on the dev machine yet: iOS code cannot be compiled here. Keep `iosMain` simple and by-the-book. |
| `desktopApp/` | JVM desktop shell — dev/verification harness only, not a product target | `./gradlew :desktopApp:run` |

Versions are pinned in `gradle/libs.versions.toml` (Kotlin 2.4.10, Compose MP 1.11.1, Ktor 3.5.1, AGP 9.1.1, Gradle 9.6.1).
These are newer than most training data: when an API doesn't compile, read the actual library sources/jars in `~/.gradle/caches` rather than guessing.
You may add libraries to the version catalog (append; mention it in your report).

## Build & test

```
docker compose up -d                 # Postgres 17 on 127.0.0.1:5434 (campus/campus, db campus_market) — already running
./gradlew :server:test               # integration tests; each test JVM creates + drops its own database
./gradlew :server:run                # http://localhost:8080 (applies migrations on boot)
./gradlew :desktopApp:run            # desktop build of the app
./gradlew :androidApp:assembleDebug
./gradlew :shared:jvmTest
```
`local.properties` (gitignored) must contain `sdk.dir=<path to your Android SDK>`; in a fresh git worktree copy it from the main checkout first.

## Wire conventions

- JSON everywhere, serialized with `com.campusmarket.core.ApiJson`. Paths come from `ApiRoutes`; limits from `Limits`.
- ids: UUID strings. Timestamps: epoch millis. Money: integer cents + `"usd"`. Enums: Kotlin names.
- Auth: `Authorization: Bearer <accessToken>` (HS256 JWT, 15 min). Refresh tokens are opaque, rotated on use, stored hashed.
- Errors: non-2xx responses carry `ErrorResponse(code, message, fields)`; codes in `ErrorCodes`. Validation = 400 `VALIDATION` with per-field messages in `fields`. Malformed/unknown ids = 404.
- Pagination: `Page<T>(items, nextCursor)`; request with `?cursor=<opaque>&limit=<n>` (default `Limits.PAGE_DEFAULT`, max `PAGE_MAX`). `nextCursor == null` means end.
- Campus scoping: a user's campus is their email domain (`campus_domain`). Listings are only visible/searchable/purchasable within the same campus. Cross-campus access to a listing or to chat = 404.
- Blocks: if either user blocked the other, they can't start conversations, send messages or create orders with each other (403 `BLOCKED`), and the blocked user's listings are hidden from the blocker's browse/search.
- Suspended / deleted / unverified users can't authenticate (handled centrally in `Security.kt`).

## Endpoints

All under `/api/v1`. 🔒 = requires auth. Types are from `core/Models.kt`.

### Auth — owner: server-auth (`server/.../auth/`)
| | | |
|---|---|---|
| POST `/auth/register` | `RegisterRequest` → `RegisterResponse` | Email must end with an allowed suffix (`.edu` by default) else 400 `EMAIL_NOT_ALLOWED`. Verified duplicate → 409 `EMAIL_TAKEN`; unverified duplicate → overwrite + resend (the pending password is replaced, so verification must prove knowledge of it, see `/auth/verify`). Creates an unverified user, emails a 6-digit code (15 min expiry, max 5 attempts). In `devMode` the code is also returned as `devCode` and logged. |
| POST `/auth/verify` | `VerifyEmailRequest` → `AuthResponse` | Requires the pending account's `password` as well as the code (anti pre-hijack: someone who re-registered a victim's address with their own password can't get the victim to verify it). Wrong/expired code, wrong password, unknown or already-verified email → identical 400 `INVALID_CODE`; a wrong password counts as a failed attempt. Marks verified; grants ADMIN if email ∈ `adminEmails`. |
| POST `/auth/resend-code` | `ResendCodeRequest` → `RegisterResponse` | Always 200 for unknown emails (no enumeration). |
| POST `/auth/login` | `LoginRequest` → `AuthResponse` | 401 `INVALID_CREDENTIALS`; 403 `EMAIL_NOT_VERIFIED`; 403 `ACCOUNT_SUSPENDED`. |
| POST `/auth/refresh` | `RefreshRequest` → `AuthResponse` | Rotates. Reuse of a revoked token revokes all of that user's tokens. 401 on failure. |
| POST `/auth/logout` | `LogoutRequest` → `OkResponse` | Revokes that refresh token. |
| POST `/auth/delete-account` | `DeleteAccountRequest(email, password)` → `OkResponse` | Account deletion **without a session** (suspended users can't hold one, but the stores require every user to be able to delete their account). Same rules and effects as `DELETE /me` (one implementation, `users/AccountDeletion.kt`), for suspended and unsuspended verified accounts. Unknown email / wrong password / deleted account → identical 401 `INVALID_CREDENTIALS` (dummy bcrypt, like login); only with the password proven: unverified → 403 `EMAIL_NOT_VERIFIED`, orders in progress → 409 `CONFLICT`. Shares the per-IP login rate-limit bucket. |

Passwords: bcrypt (`at.favre.lib:bcrypt`, cost 12; cost 4 is fine in tests). Rate-limit register/login/verify/resend per IP (Ktor `RateLimit`; make limits configurable so tests aren't flaky). Email goes through a small `Mailer` interface: `LogMailer` (dev) and an SMTP implementation used when `SMTP_HOST` is set.

### Users — owner: server-auth (`server/.../users/`)
| | | |
|---|---|---|
| 🔒 GET `/me` | → `UserPrivate` | |
| 🔒 PATCH `/me` | `UpdateProfileRequest` → `UserPrivate` | Avatar photo must be owned by caller. |
| 🔒 DELETE `/me` | → `OkResponse` | Account deletion (store requirement): soft-delete (`deleted_at`), anonymise email/name, revoke tokens, mark own ACTIVE/RESERVED listings DELETED. Refuse with 409 while the user has orders in `PENDING_PAYMENT`, `PAID` or `DISPUTED` (as buyer or seller). Suspended users use `POST /auth/delete-account`. |
| 🔒 GET `/users/{id}` | → `UserPublic` | Same campus only; else 404. |
| 🔒 POST / DELETE `/users/{id}/block` | → `OkResponse` | Idempotent. Can't block yourself. |
| 🔒 GET `/me/blocks` | → `List<UserPublic>` | |

### Photos — owner: server-listings (`server/.../photos/`)
| | | |
|---|---|---|
| 🔒 POST `/photos` | multipart, part name `file` → `Photo` | JPEG/PNG/WebP only (sniff magic bytes, don't trust the header), ≤ `PHOTO_MAX_BYTES`. Stored via a `PhotoStorage` interface; `LocalPhotoStorage` writes to `config.uploadsDir` with a random name. Record width/height when cheaply decodable. |
| GET `/uploads/{key}` | static file | Public, long cache headers, no directory traversal. |

### Listings — owner: server-listings (`server/.../listings/`)
| | | |
|---|---|---|
| 🔒 POST `/listings` | `CreateListingRequest` → `ListingDetail` | Validate with `Limits`. Photos must be owned by caller. Status ACTIVE. |
| 🔒 GET `/listings` | → `Page<ListingSummary>` | Query: `q` (full-text on the `search` tsvector, prefix-match the last term; fall back to ILIKE for very short queries), `category`, `condition` (comma-separated allowed), `minPrice`, `maxPrice` (cents), `sort` (`ListingSort`; default NEWEST, or RELEVANCE when `q` is present), `cursor`, `limit`. Only ACTIVE listings on the caller's campus, excluding sellers the caller has blocked. Keyset pagination. |
| 🔒 GET `/listings/{id}` | → `ListingDetail` | Same campus. ACTIVE/RESERVED/SOLD visible to everyone on campus; REMOVED/DELETED only to the seller and moderators (else 404). `blocked` = a block exists between viewer and seller (either direction). |
| 🔒 PATCH `/listings/{id}` | `UpdateListingRequest` → `ListingDetail` | Seller only; only while ACTIVE. |
| 🔒 DELETE `/listings/{id}` | → `OkResponse` | Seller only; sets DELETED. 409 if RESERVED/SOLD. |
| 🔒 POST / DELETE `/listings/{id}/favorite` | → `OkResponse` | Idempotent. |
| 🔒 GET `/me/favorites` | → `Page<ListingSummary>` | Newest-favorited first; hides REMOVED/DELETED. |
| 🔒 GET `/me/listings` | → `Page<ListingSummary>` | Caller's listings, all statuses except DELETED. Optional `status`. |
| 🔒 GET `/users/{id}/listings` | → `Page<ListingSummary>` | That user's ACTIVE listings (same campus). |
| 🔒 GET `/categories` | → `List<CategoryInfo>` | With ACTIVE counts for the caller's campus. |

### Chat — owner: server-chat (`server/.../chat/`)
| | | |
|---|---|---|
| 🔒 POST `/conversations` | `StartConversationRequest` → `Conversation` | Caller = buyer. Idempotent per (listing, buyer). Can't message your own listing (400). Listing must be visible to the caller. Optional `initialMessage` is sent as the first message. |
| 🔒 GET `/conversations` | → `Page<Conversation>` | Conversations where caller is buyer or seller, most recent activity first, with `unreadCount`. |
| 🔒 GET `/conversations/{id}` | → `Conversation` | Participants only (else 404). |
| 🔒 GET `/conversations/{id}/messages` | → `Page<Message>` | Newest first; `cursor` pages backwards in time. |
| 🔒 POST `/conversations/{id}/messages` | `SendMessageRequest` → `Message` | 1..`MESSAGE_MAX` chars after trim. Updates conversation preview/last_message_at. 403 `BLOCKED` if blocked either way. |
| 🔒 POST `/conversations/{id}/read` | → `OkResponse` | Marks read up to now for the caller. |
| WS `/ws?token=<accessToken>` | text frames | Server → client: `ChatEvent` JSON. Client → server: `ChatCommand` JSON. Invalid token: close with policy violation. Every participant's open sockets (any number of devices) get `MessageCreated` for messages sent via REST or WS, and `ConversationRead`. In-process hub is fine (single node); keep the hub behind an interface. |

### Orders & payments — owner: server-payments (`server/.../payments/`)

Model: Stripe Connect **Express** accounts, **separate charges and transfers**. The buyer pays the platform through
hosted **Stripe Checkout**; funds are held on the platform; when the buyer confirms the handoff — or automatically
`AUTO_RELEASE_HOURS` (default 72) after payment — the item price minus the platform fee is transferred to the seller's
connected account (`transfer_group` = order id, `source_transaction` = the charge). Only the **seller** can cancel a paid
order (which refunds the buyer); the buyer, who may already hold the item, **reports a problem** instead (a dispute),
which freezes the payment until the buyer withdraws it or confirms receipt, the seller refunds, or a moderator decides.
No card data ever touches our server or apps.

```
PENDING_PAYMENT ─paid─▶ PAID ─buyer confirms / AUTO_RELEASE_HOURS pass─▶ COMPLETED (transfer, listing SOLD)
      │                  │ ▲                                              ▲ buyer confirms / moderator releases
      │                  │ └─buyer withdraws (clock restarts)─┐           │
      │                  ├─buyer reports a problem / seller suspended─▶ DISPUTED
      └─cancel/expire─▶ CANCELLED  └─seller cancels─▶ REFUNDED ◀─seller cancels / moderator refunds─┘
```
Live statuses (an order holding its listing RESERVED; `orders_one_live_per_listing`): PENDING_PAYMENT, PAID, DISPUTED.
Every money movement on a held order (PAID/DISPUTED) first claims it (`orders.pending_action` TRANSFER | REFUND):
re-entrant for the same action (same Stripe idempotency key, so a buyer confirm racing the auto-release still moves
money once), exclusive across actions (never both a transfer and a refund).

| | | |
|---|---|---|
| 🔒 GET `/payments/account` | → `PayoutAccountStatus` | Refreshes from Stripe when an account exists. |
| 🔒 POST `/payments/account/onboarding` | → `OnboardingLinkResponse` | Creates the Express account on first call (stores `stripe_account_id`), returns an Account Link URL to open in the browser. Return/refresh URLs: `{publicBaseUrl}/api/v1/payments/return?...` pages that bounce to `{appDeepLinkBase}payouts`. |
| 🔒 POST `/orders` | `CreateOrderRequest` → `Order` | Buyer ≠ seller, same campus, not blocked, listing ACTIVE (else 409 `LISTING_UNAVAILABLE`), seller `payouts_enabled` (else 409 `PAYOUTS_NOT_ENABLED`). Atomically: create order PENDING_PAYMENT (the partial unique index enforces one live order per listing), set listing RESERVED, create a Checkout Session (30 min expiry; success/cancel URLs bounce to `{appDeepLinkBase}orders/{id}`), store session id + URL. `feeCents = amount * platformFeeBps / 10000`. |
| 🔒 GET `/orders` | → `Page<Order>` | `role=buyer|seller` (default both), newest first. |
| 🔒 GET `/orders/{id}` | → `Order` | Buyer or seller only. `autoReleaseAt` (PAID only): when the payment is released automatically. `dispute`: the latest `OrderDispute` (open or closed; no moderator identity or note). |
| 🔒 POST `/orders/{id}/confirm` | → `Order` | Buyer only (seller → 403), PAID or DISPUTED → COMPLETED: transfer to seller, listing → SOLD; an open dispute closes as `BUYER_CONFIRMED`. Else 409 `INVALID_ORDER_STATE`. |
| 🔒 POST `/orders/{id}/cancel` | → `Order` | PENDING_PAYMENT: buyer or seller → CANCELLED (expire the session), listing → ACTIVE (if the buyer paid at that very moment: the seller's cancel refunds, the buyer's leaves it PAID). PAID / DISPUTED: **seller only** (buyer → 403 `FORBIDDEN`) → refund → REFUNDED, listing → ACTIVE; an open dispute closes as `SELLER_REFUNDED`. COMPLETED / CANCELLED / REFUNDED: 409. |
| 🔒 POST `/orders/{id}/dispute` | `OpenDisputeRequest(reason, details)` → `Order` | Buyer only (seller → 403), PAID → DISPUTED (else 409 `INVALID_ORDER_STATE`, also while a transfer/refund is in flight). `reason` ∈ SELLER_NO_SHOW, ITEM_NOT_AS_DESCRIBED, OTHER (SELLER_SUSPENDED → 400); `details` ≤ `Limits.DISPUTE_DETAILS_MAX`, required for OTHER, control characters dropped. Freezes the payment: no auto-release, no payout. At most 3 disputes per order (409 after). |
| 🔒 POST `/orders/{id}/dispute/withdraw` | → `Order` | Buyer only. DISPUTED → PAID, dispute closes as `WITHDRAWN`, the auto-release clock starts over. A platform hold (reason SELLER_SUSPENDED) can't be withdrawn (409). |
| POST `/stripe/webhook` | raw body | Verify signature with `stripeWebhookSecret`. Idempotent via `stripe_events`. Handle `checkout.session.completed` (→ PAID, store payment intent), `checkout.session.expired` (→ CANCELLED, listing → ACTIVE), `account.updated` (sync `stripe_details_submitted` / `payouts_enabled`), `charge.refunded`. |
| 🔒 POST `/orders/{id}/rating` | `CreateRatingRequest` → `Rating` | Buyer or seller of a COMPLETED order rates the other party once (409 on repeat). Stars 1..5. Maintains `users.rating_sum/rating_count` in the same transaction. |
| 🔒 GET `/users/{id}/ratings` | → `Page<Rating>` | Ratings received, newest first. |
| 🔒 GET `/mod/disputes` | → `Page<DisputeCase>` | Moderators only (403 otherwise). `status=OPEN` (default; oldest first) or `CLOSED` (most recently closed first). All campuses. `DisputeCase`: order id, listing, both parties (+ whether each is suspended now), amounts, order status, the dispute, and for moderator outcomes who resolved it and their note. |
| 🔒 GET `/mod/disputes/{orderId}` | → `DisputeCase` | The order's most recent dispute; 404 if it never had one. |
| 🔒 POST `/mod/disputes/{orderId}/resolve` | `ResolveDisputeRequest(resolution, note)` → `DisputeCase` | `REFUND_BUYER` → REFUNDED (listing ACTIVE) or `RELEASE_TO_SELLER` → COMPLETED (transfer, listing SOLD), via the same claims as the parties' actions. `note` required (≤ `Limits.DISPUTE_NOTE_MAX`), internal. Writes a `moderation_actions` row `RESOLVE_DISPUTE` (target type ORDER, target id = order id) in the same transaction as the outcome. Order not DISPUTED → 409; the moderator is the buyer or seller → 403. (Lives in `payments/` because it moves money.) |

All Stripe access goes through a `PaymentGateway` interface with two implementations:
`StripePaymentGateway` (stripe-java, used when `STRIPE_SECRET_KEY` is set) and `FakePaymentGateway` (default in dev/tests; no network).
The fake gateway makes the whole flow usable locally: onboarding URL → a server-rendered page `GET /api/v1/dev/fake-stripe/onboarding/...` with a "Complete onboarding" button that enables payouts;
checkout URL → `GET /api/v1/dev/fake-stripe/checkout/{orderId}` with "Pay" / "Cancel" buttons that drive the same state transitions as the real webhooks. Dev routes are only mounted when the fake gateway is active.
A background job (coroutine, every minute; one instance only) runs the payments sweep, each part independent and retried
on the next run after a failure:
1. cancels PENDING_PAYMENT orders older than 35 minutes;
2. **suspended sellers** (read from `users.suspended` / `suspended_until`, no import of the moderation feature): their
   PENDING_PAYMENT orders are cancelled (checkout session expired first), and their PAID orders become DISPUTED with
   reason `SELLER_SUSPENDED` (opened by the platform) for a moderator to refund or release. Nothing is ever
   auto-released to a suspended seller, and a checkout paid after the seller was suspended is cancelled and refunded
   at payment time;
3. **auto-release**: PAID orders whose clock (`orders.release_clock_from`: the payment, or the last withdrawn dispute)
   started `AUTO_RELEASE_HOURS` ago are released to the seller exactly like a buyer confirmation. A suspended buyer's
   orders are settled this way (or by a moderator).

Refunds or disputes started from the Stripe dashboard after COMPLETED stay manual (`charge.refunded` on a completed
order is only logged: reverse the transfer by hand).

### Reports & moderation — owner: server-moderation (`server/.../moderation/`)
| | | |
|---|---|---|
| 🔒 POST `/reports` | `CreateReportRequest` → `OkResponse` | Target must exist and be visible to the reporter (MESSAGE: reporter must be a participant). Can't report yourself / your own content. One OPEN report per (reporter, target) — repeat is a no-op 200. Snapshot `target_summary` and `target_user_id`. Target type ORDER (log-only) → 400: problems with an order are disputes (`POST /orders/{id}/dispute`). |
| 🔒 GET `/mod/reports` | → `Page<Report>` | Moderators only (403 otherwise). `status` filter (default OPEN), oldest first for OPEN, newest first otherwise. Moderators see all campuses. |
| 🔒 GET `/mod/reports/{id}` | → `Report` | |
| 🔒 POST `/mod/reports/{id}/resolve` | `ResolveReportRequest` → `Report` | Applies the action (DISMISS → status DISMISSED; REMOVE_LISTING / SUSPEND_USER / WARN_USER → RESOLVED), writes `moderation_actions`. Resolving also resolves other OPEN reports on the same target with the same action. 409 if not OPEN. |
| 🔒 POST `/mod/users/{id}/suspend` | `SuspendUserRequest` → `OkResponse` | Sets `suspended`, `suspended_until` (null = indefinite), reason; revokes refresh tokens. Moderators can't suspend moderators/admins (403); admins can suspend moderators. |
| 🔒 POST `/mod/users/{id}/unsuspend` | `ModerationNoteRequest` → `OkResponse` | |
| 🔒 POST `/mod/listings/{id}/remove` | `ModerationNoteRequest` → `OkResponse` | ACTIVE → REMOVED. 409 if RESERVED/SOLD. |
| 🔒 POST `/mod/listings/{id}/restore` | `ModerationNoteRequest` → `OkResponse` | REMOVED → ACTIVE. |
| 🔒 GET `/mod/log` | → `Page<ModerationLogEntry>` | Newest first. Includes `RESOLVE_DISPUTE` entries (target type ORDER) from the dispute queue (see Orders). |
| 🔒 GET `/mod/staff` | → `List<StaffMember>` | **Admins only** (403 for moderators). Verified, non-deleted moderators and admins, admins first then by name. `StaffMember(user: UserPublic, role, email)`. |
| 🔒 GET `/mod/users/search?q=` | → `List<StaffMember>` | **Admins only.** Case-insensitive substring match on email or display name among verified, non-deleted users on any campus; `q` shorter than 2 chars (after trim) → `[]`, longer than 100 → 400; max 20 results, ordered by name. |
| 🔒 PUT `/mod/users/{id}/role` | `SetRoleRequest(role)` → `StaffMember` | **Admins only.** Only `USER` ↔ `MODERATOR`: assigning `ADMIN` → 400 (`fields.role`; admins come only from `ADMIN_EMAILS`); own id → 400; target already an ADMIN → 403; target missing / unverified / deleted → 404. Setting the role the user already has is a no-op 200 (no log row). Otherwise updates the role (effective immediately: roles are re-read on every request) and writes a `moderation_actions` row `CHANGE_ROLE` (target type USER, note `"USER -> MODERATOR"`). |

### Legal pages — `server/.../legal/`
| | | |
|---|---|---|
| GET `/legal/terms`, `/legal/privacy`, `/legal/guidelines` | → server-rendered HTML | Public (no auth), not under `/api`. Starter templates (marked as such on the page; counsel must review before launch). Company name / contact come from `LEGAL_ENTITY_NAME` / `SUPPORT_EMAIL`, else visible placeholders. Strict CSP (no scripts; the one inline stylesheet is allowed by hash). The client opens them in the browser via `ExternalLinks` (Settings → About, register footer). |

## Server architecture rules

- Foundation files are owned by the tech lead: `Application.kt`, `Config.kt`, `Db.kt`, `Errors.kt`, `Security.kt`, `Readers.kt`, `V1__init.sql`, `TestSupport.kt`. Read them first — they are the toolkit. Change them only if you truly must, minimally, and flag it.
- Each feature implements its `Route.<feature>Routes(deps: AppDeps)` (already wired in `Application.kt`) and keeps all its code in its own package. Wrap protected routes in `authenticate(AUTH_JWT) { }`; get the caller with `call.user` / `call.userId`; moderators via `call.requireModerator()`.
- Throw `ApiException` (helpers: `badRequest`, `invalidField`, `notFound`, `forbidden`, `conflict`, `unauthorized`) — never hand-build error JSON.
- SQL: hand-written, parameterised, via `deps.db.tx { c -> c.query(...) / c.queryOne(...) / c.update(...) }`. One transaction per request unless you have a reason. Never call the network (Stripe) while holding a transaction open across it if you can avoid it; make state transitions conditional (`UPDATE ... WHERE status = ?`) so they're race-safe.
- Render users and listing summaries only through `Readers.kt` (`loadUsersPublic`, `loadListingSummaries`, …).
- Schema changes: add `server/src/main/resources/db/migration/V2_<feature>_<n>__<desc>.sql`. Never edit V1.
- Features touch each other only through the database (e.g. moderation sets `listings.status = 'REMOVED'`; payments sets `RESERVED`/`SOLD`). Don't import another feature's package.
- Tests: integration tests in `server/src/test/kotlin/com/campusmarket/server/<feature>/` using `apiTest { env -> }` and the `TestEnv` fixtures (`env.user()`, `env.listing()`, …), which insert rows directly so your tests don't depend on other features' endpoints. Cover the happy paths and every rule in the tables above (authz, campus scoping, blocks, state machines, validation).

## Config (env vars → `AppConfig`)

`PORT`, `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`, `PUBLIC_BASE_URL`, `JWT_SECRET`, `DEV_MODE`, `ALLOWED_EMAIL_SUFFIXES`, `ADMIN_EMAILS`,
`UPLOADS_DIR`, `STRIPE_SECRET_KEY`, `STRIPE_WEBHOOK_SECRET`, `PLATFORM_FEE_BPS`, `APP_DEEP_LINK_BASE`, `SMTP_HOST`, `SMTP_PORT`, `SMTP_USER`, `SMTP_PASSWORD`, `MAIL_FROM`, `LEGAL_ENTITY_NAME`, `SUPPORT_EMAIL`.
`AUTO_RELEASE_HOURS` (default 72, whole hours ≥ 1) is read by the payments feature (`PaymentOptions.fromEnv`); it applies
to every waiting order (the deadline is computed at read time). The Terms (`/legal/terms`) state 72 hours: change them
together.

## App (client) product scope

Bottom navigation: **Browse** (search, category chips, filters, listing grid) · **Sell** (create listing with photos) · **Inbox** (conversations) · **Profile** (me, my listings, favorites, orders, payouts, settings, moderation queue for moderators).

Flows: register → verify code → signed in; login; browse/search/filter; listing detail (photo pager, seller card, favorite, message seller, buy, report); create/edit/delete listing; chat (live via websocket, REST fallback); buy → open Stripe Checkout in the browser → return via `campusmarket://orders/{id}` deep link → order detail (auto-release deadline; buyer: confirm receipt / report a problem, withdraw it; seller: cancel and refund) → rate; seller payout onboarding; public profile with ratings and listings, block/report user; moderation queue + actions for moderators, dispute queue (refund buyer / release to seller); account deletion (also for suspended accounts, from the sign-in / signed-out screen); sign out.

Client architecture is defined by the client foundation work and documented in `docs/CLIENT.md`.

## Git

Work only inside your owned paths. Commit your work on your branch when done (small logical commits are fine); don't push anywhere. Don't touch files owned by others; if you need something from another owner, work around it via the contract and note it in your report.
