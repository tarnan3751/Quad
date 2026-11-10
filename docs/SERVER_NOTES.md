# Server behaviour notes for client work

Decisions made while implementing the server that go beyond `CONTRACT.md`. Client code should follow these.

## Status codes
- `POST /listings` and `POST /photos` return **201**. Everything else returns 200 on success. Treat any 2xx as success.
- Rate limits: 429 `RATE_LIMITED`. Stripe failures: 502 `PAYMENT_FAILED`.
- Per-user limits (generous; show the error `message`): creating listings, sending messages (REST + WS together), starting new
  conversations (re-opening an existing one is free), creating reports, and creating orders. A buyer may also have at
  most **3 orders awaiting payment** at once: the 4th `POST /orders` is 429 `RATE_LIMITED` until one is paid or cancelled.
- Request bodies (except photo uploads) must declare a `Content-Length` (else 411) of at most 1 MiB (else 413 `VALIDATION`).
- Free text: control characters are dropped from messages, rating comments and report details (instead of failing).
  Invisible formatting characters (bidi overrides, zero-width spaces, …) are stripped from listing titles, descriptions and
  pickup locations, and rejected in display names and bios (400 `VALIDATION`); emoji ZWJ sequences are fine.

## Auth
- Register returns 200 `RegisterResponse` (`devCode` is set in dev mode — prefill / show it).
- Resend within 30 s of the last code is a silent no-op (200, `devCode` null): show a 30 s countdown on the resend button.
- **Only one refresh request may be in flight at a time.** Two concurrent refreshes with the same token count as token reuse and sign the user out everywhere.
- Refresh for a suspended user: 403 `ACCOUNT_SUSPENDED`; other refresh failures: 401 → sign out locally.
- `INVALID_CODE` carries `fields.code`; `EMAIL_NOT_ALLOWED` carries `fields.email`. Verify accepts "123 456" / "123-456". Verify also requires `password` (the pending account's password, checked with bcrypt every time, even for unknown emails); a wrong password is indistinguishable from a wrong code (`INVALID_CODE`) and consumes an attempt. The client keeps the password in memory only (`PendingVerification`) between register/login and verify; if it is gone (process death) the verify screen redirects to login.
- `PATCH /me` with `avatarPhotoId: ""` clears the avatar; bad photo id → 400 with `fields.avatarPhotoId`.
- `DELETE /me` → 409 `CONFLICT` while the user has orders in PENDING_PAYMENT, PAID or DISPUTED.
- Suspended accounts: login, refresh and every authenticated call answer 403 `ACCOUNT_SUSPENDED` with
  "Your account is suspended: Violation of community guidelines". Show it, and offer deletion through
  `POST /auth/delete-account` (`DeleteAccountRequest(email, password)`, no bearer token): 200 `OkResponse` on success;
  401 `INVALID_CREDENTIALS` for any wrong email/password (indistinguishable); 403 `EMAIL_NOT_VERIFIED`; 409 `CONFLICT`
  while orders are in progress (a suspended user's paid orders settle by the 72 h auto-release or a moderator, so
  "try again later"); 429 after too many attempts (shares the login budget). Ask for the password again; never reuse
  one kept from the login form.
- Blocking yourself → 400. Deleted users render as "Deleted user".

## Photos
- Multipart part name `file`; the part **must include a filename** in Content-Disposition and the request must have a Content-Length (no chunked upload) or the server answers 411.
- > 8 MB → 413 `VALIDATION` (`fields.file`); unsupported / corrupt → 400 (`fields.file`). JPEG/PNG/WebP only. Downscale/compress large photos client-side to JPEG before upload where practical.

## Listings
- `GET /listings` params: `q`; `category`, `condition` (enum names, comma-separated); `minPrice`, `maxPrice` (cents, inclusive); `sort` (`NEWEST|PRICE_ASC|PRICE_DESC|RELEVANCE`; default RELEVANCE with `q`, else NEWEST); `limit`; `cursor`.
- A cursor is only valid with the sort/filters that produced it: restart from page 1 whenever query, filters or sort change.
- `/me/listings?status=ACTIVE,SOLD` uses the same list format.
- Edit/delete someone else's listing → 403; editing a non-ACTIVE listing → 409; deleting RESERVED/SOLD → 409.

## Chat
- Cursors are opaque; messages come newest-first; conversations ordered by last activity.
- Send: body trimmed, 1..2000 chars; errors in `fields.body` (or `fields.initialMessage` on start).
- Blocked either way: history readable, send → 403 `BLOCKED`. Sending to a deleted user → 409.
- WebSocket: `GET /api/v1/ws?token=<accessToken>`. Bad/expired token → accepted then closed with VIOLATED_POLICY (1008): refresh the access token, then reconnect.
  An **open** socket is also closed with 1008 when its access token expires (so about every 15 minutes) and within ~30 s
  of the account being suspended or deleted. Same handling: refresh, reconnect (a suspended account's refresh fails with
  403 `ACCOUNT_SUSPENDED`), and re-fetch via REST anything that may have arrived in between.
- Events fan out to all sockets of both participants, **including the sender's own socket** — de-duplicate messages by id.
- A rejected WS command is silently dropped (no error frame). **Send messages via REST** (so failures are visible) and use the socket for receiving; use WS `Ping` for keep-alive if needed.
- Mark-read always emits `ConversationRead` to both sides.

## Orders & payments
- `Order.checkoutUrl` is present for the buyer while PENDING_PAYMENT: open it in the external browser.
- The browser returns to the app via `campusmarket://orders/{orderId}` (after pay or cancel) and `campusmarket://payouts` (after payout onboarding). On return just call `GET /orders/{id}` / `GET /payments/account` — the server catches up with Stripe itself. Also refresh these when the app resumes, in case the deep link didn't fire.
- In dev (fake gateway) the checkout/onboarding URLs are server-rendered pages with Pay / Cancel / Complete buttons.
- Buying your own listing → 400; seller without payouts → 409 `PAYOUTS_NOT_ENABLED`; listing not ACTIVE → 409 `LISTING_UNAVAILABLE`; blocked → 403 `BLOCKED`.
- Confirm (buyer only, PAID or DISPUTED) → COMPLETED. Cancel: PENDING_PAYMENT → CANCELLED (buyer or seller);
  PAID / DISPUTED → REFUNDED (**seller only**; the buyer gets 403 `FORBIDDEN`); otherwise 409 `INVALID_ORDER_STATE`.
  A buyer's cancel of an unpaid order that got paid at that very moment answers 200 with the order still PAID (not
  refunded): say "the payment came through, so the order wasn't cancelled".
- **Hold and auto-release.** While PAID, `Order.autoReleaseAt` is when the payment goes to the seller automatically
  (72 h after payment by default; restarted when the buyer withdraws a dispute). Show it to both sides ("Funds release
  to the seller on … unless you report a problem"). It is null in every other status. The order may turn COMPLETED on
  its own: refresh on resume.
- **Disputes.** The buyer of a PAID order reports a problem with `POST /orders/{id}/dispute`
  (`OpenDisputeRequest(reason, details)`; reasons SELLER_NO_SHOW, ITEM_NOT_AS_DESCRIBED, OTHER; details ≤ 1000, required
  for OTHER; errors in `fields.reason` / `fields.details`) → DISPUTED, payment frozen. 409 `INVALID_ORDER_STATE` if
  it's not PAID any more, a release/refund is already in flight, or after 3 disputes on one order. While DISPUTED: the
  buyer can `POST /orders/{id}/dispute/withdraw` (→ PAID, clock restarts) or confirm; the seller can cancel (refund);
  a moderator refunds or releases. `Order.dispute` is the latest dispute: `status` OPEN/CLOSED, `outcome` (WITHDRAWN,
  BUYER_CONFIRMED, SELLER_REFUNDED, REFUNDED_BY_MODERATOR, RELEASED_BY_MODERATOR, REFUNDED), `openedBySystem` (the
  platform put the order on hold because the seller was suspended; reason SELLER_SUSPENDED; the buyer can't withdraw
  it but may still confirm). The moderator's note and identity are never sent to the parties.
- Rating: both parties, once each, only when COMPLETED; `Order.iHaveRated` tells you whether to offer it.
- `GET /orders/{id}` and `GET /payments/account` refresh from Stripe at most ~30 times a minute per user; past that they
  return the stored state (still kept current by webhooks), so polling them in a tight loop gains nothing.

## Reports & moderation
- `POST /reports` repeat is a no-op 200. Reporting yourself/your own content → 400.
- Resolve: valid actions are DISMISS, REMOVE_LISTING (LISTING reports only), SUSPEND_USER (optional `suspendDays` 1..3650, null = indefinite), WARN_USER. RESTORE_LISTING / UNSUSPEND_USER are only for the direct endpoints / log display.
- Direct suspend needs a non-blank `reason`. Moderators can only act on USER-role accounts; admins also on moderators.
- The suspended user is only ever told "Violation of community guidelines" (in the 403 `ACCOUNT_SUSPENDED` message).
  The moderator's resolution note / direct-suspend reason stay internal (moderation log and report), since they can
  name the reporter.
- `GET /mod/reports?status=OPEN|RESOLVED|DISMISSED`.
- `ReportTargetType.ORDER` and `ModerationAction.RESOLVE_DISPUTE` were appended for the moderation log (render them
  as "Order" / "Resolve dispute"; the log entry's `targetId` is the order id). Reporting an ORDER → 400.

## Disputes (moderators)
- `GET /mod/disputes?status=OPEN|CLOSED` (default OPEN, oldest first; CLOSED newest first) → `Page<DisputeCase>`;
  `GET /mod/disputes/{orderId}` → the order's latest `DisputeCase`. 403 for non-moderators.
- `POST /mod/disputes/{orderId}/resolve` with `ResolveDisputeRequest(REFUND_BUYER | RELEASE_TO_SELLER, note)`: the note
  is required (400 `fields.note`) and internal. 409 `INVALID_ORDER_STATE` when the dispute is no longer open (the
  buyer withdrew/confirmed, the seller refunded, another moderator acted) or money is already moving: reload. 403 when
  the moderator is the buyer or seller. 502 `PAYMENT_FAILED`: the processor failed; retrying is safe.
- `DisputeCase.buyerSuspended` / `sellerSuspended` are the accounts' current state; `resolvedBy` / `resolutionNote`
  are set for moderator outcomes.

## Staff management (admins)
- `ModerationAction.CHANGE_ROLE` is a log-only action appended to the enum (the column is plain text, no migration). It is never valid for resolving a report (400); render it in the moderation log as "Change role" (`ui/Labels.kt`).
- `GET /mod/staff`, `GET /mod/users/search?q=` and `PUT /mod/users/{id}/role` are **ADMIN only** (moderators get 403). Hide the entry points for non-admins; the app shows a "Staff" button in the moderation queue's app bar for admins.
- Admins are never created or altered through the API: they come from `ADMIN_EMAILS` on verification. The role endpoint can't target an admin (403), can't be pointed at yourself (400) and can't assign `ADMIN` (400). Promote/demote `USER` ↔ `MODERATOR` only.
- Role changes take effect on the target's next request (no re-login needed); the audit row (`moderator_id` = the admin, note `"<old> -> <new>"`) shows up in `/mod/log`.
- `StaffMember.email` is returned to admins only; never show it elsewhere.
- Search needs >= 2 characters (shorter returns an empty list, so debounce or search on submit) and returns at most 20 rows.

## Demo data (after `./gradlew :server:seed`)
Password `password123`: `admin@state.edu` (ADMIN), `moderator@state.edu` (MODERATOR), sellers `maya@ jordan@ priya@ sofia@state.edu`, buyers `liam@ noah@state.edu`; `dev@ ava@ ethan@ zoe@tech.edu` on the second campus.
Paid demo orders were paid a few hours before seeding (their 72 h hold runs from then); one of them (zoe@tech.edu buying
from ava@tech.edu) is disputed, so the moderators' dispute queue isn't empty.
