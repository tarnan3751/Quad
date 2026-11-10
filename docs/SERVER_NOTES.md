# Server behaviour notes for client work

Decisions made while implementing the server that go beyond `CONTRACT.md`. Client code should follow these.

## Status codes
- `POST /listings` and `POST /photos` return **201**. Everything else returns 200 on success. Treat any 2xx as success.
- Rate limits: 429 `RATE_LIMITED`. Stripe failures: 502 `PAYMENT_FAILED`.
- Per-user limits (generous; show the error `message`): creating listings, sending messages (REST + WS together), starting new
  conversations (re-opening an existing one is free), creating reports, and creating orders. A buyer may also have at
  most **3 orders awaiting payment** at once: the 4th `POST /orders` is 429 `RATE_LIMITED` until one is paid or cancelled.
- Request bodies (except photo uploads) must declare a `Content-Length` (else 411) of at most 1 MiB (else 413 `VALIDATION`).
  A JSON body that can't be read (malformed, missing fields, missing or wrong `Content-Type`) is 400 `VALIDATION` on every
  endpoint.
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
- **Variants.** Every photo also gets server-made JPEGs (upright, no metadata, transparency on white, never upscaled):
  `Photo.thumbnailUrl` (480 px on the short side, at most 1280 px on the long side) and `Photo.displayUrl` (at most
  1280 px on the long side). They are made during the upload (the response carries them) and, for photos stored before
  variants existed, by a background pass at server start (`PHOTO_VARIANT_BACKFILL=false` turns it off). A variant URL
  may equal `url` when the original is already a small upright JPEG. They are null when there is none (WebP originals:
  the JDK can't decode WebP; corrupt or CMYK JPEGs): fall back to `url`.
- `GET /uploads/{key}` (originals and variants): `Cache-Control: public, max-age=31536000, immutable`, strong `ETag`,
  `Last-Modified`, 304 for `If-None-Match` / `If-Modified-Since`, single byte ranges (206; 416 when unsatisfiable;
  `If-Range` honoured), `HEAD`.
- **Which URL to load** (every variant field is optional: always fall back to the original):
  - grid tiles, inbox / order / moderation rows: `ListingSummary.thumbnailSmallUrl ?: thumbnailUrl` (`thumbnailUrl` is
    the full-size original of the first photo). `thumbnailWidth` / `thumbnailHeight` are the first photo's upright size
    (same aspect ratio as the small variant) for reserving space.
  - avatars: `UserPublic.avatarThumbnailUrl ?: avatarUrl` (also on `UserPrivate`); no avatar is shown above ~112 dp.
  - listing photo pager: `Photo.displayUrl ?: url`; the zoomable full-screen viewer: `Photo.url`; the editor's photo
    strip: `Photo.thumbnailUrl ?: url` (an upload's response already carries its variants).
  - A browse page of 20 seed listings loads 0.91 MB of thumbnails instead of 1.93 MB of originals; phone photos
    (2048 px, re-encoded by the app) shrink about 9x.

## HTTP
- JSON and HTML bodies of 1 KB or more are gzip/deflate-compressed when the request accepts it (OkHttp and URLSession
  send `Accept-Encoding` and unpack transparently, so nothing to do): a page of 20 listings is ~2.8 KB on the wire instead
  of ~11 KB. Photos and websocket frames are never compressed.
- `GET /categories` (and the `/legal` pages) carry an `ETag`; categories are `Cache-Control: private, no-cache`, so a
  client cache revalidates on every use and an unchanged result costs a 304 without a body. URLSession does this by
  itself; on Android it needs an HTTP cache on the client (e.g. Ktor's `HttpCache` plugin).
- Paging cursors stay opaque; cursors issued before this release are still accepted.

## Listings
- `GET /listings` params: `q`; `category`, `condition` (enum names, comma-separated); `minPrice`, `maxPrice` (cents, inclusive); `sort` (`NEWEST|PRICE_ASC|PRICE_DESC|RELEVANCE`; default RELEVANCE with `q`, else NEWEST); `limit`; `cursor`.
- `q` of three or more letters/digits is a full-text search (stemmed, all terms required, the last term matches as a
  prefix so results follow the typing). Shorter queries, stop words ("the") and punctuation ("%") match as a
  case-insensitive substring of the title or description.
- `RELEVANCE` is three classes, each newest first: every term in the title, then some of the terms in the title, then
  description only (substring queries: title starts with the text, title contains it, description only). It is not a
  score: a listing that repeats a word doesn't move up.
- A cursor is only valid with the sort/filters that produced it: restart from page 1 whenever query, filters or sort change.
- `/me/listings?status=ACTIVE,SOLD` uses the same list format.
- Edit/delete someone else's listing → 403; editing a non-ACTIVE listing → 409; deleting RESERVED/SOLD → 409.
- `ListingDetail.blocked` is true when the viewer and the seller have blocked each other in either direction (so messaging
  and buying would answer 403 `BLOCKED`); `seller.blockedByMe` says whether the viewer did it. Show an unavailable state
  instead of Buy / Message. Always false on your own listing.

## Performance (measured)
Synthetic dataset: 300,000 listings on five campuses (the largest has 110,000 ACTIVE ones), 30,000 users, 250,000
conversations with 2.1 M messages, 106,000 orders, 400,000 favorites; rows in random physical order (worse than a real
table, which is roughly in posting order). Whole request over loopback, median of 40 after warm-up, best of three
rounds, page of 20, viewer with 40 blocks. "Before" is the release before the search indexes (V6_listings_1).

| `GET /listings` on the large campus | before | now |
|---|---|---|
| `q=a`, `q=t` (nearly everything matches) | 62-69 ms | 1.7 ms |
| `q=tv`, `q=de`, `q=ik`, `q=4k` | 63-72 ms | 2.8-4.8 ms |
| `q=zq`, `q=x9`, `q=%` (no match) | 57 ms | 5.9 ms |
| `q=the`, `q=it` (stop words) | 69-75 ms | 3.4-4.0 ms |
| `q=tv` page 2 | 69 ms | 2.9 ms |
| `q=zq&sort=NEWEST` / `PRICE_ASC` | 58 ms | 3.3-3.6 ms |
| `q=desk`, `q=calc`, `q=textbook`, `q=pickup` (common words) | 22-27 ms | 1.8-3.1 ms |
| `q=tv stand`, `q=wireless charger`, `q=ikea desk`, `q=mini fridge` | 6-16 ms | 2.8-3.9 ms |
| `q=zzzz` (rare word) | 3.0 ms | 3.3 ms |
| `q=a&category=BIKES` | 12.8 ms | 2.4 ms |
| `q=tv&category=BIKES` / `q=zq&category=BIKES` | 13.3 / 18.3 ms | 7.3 / 11.4 ms |
| no `q`: every sort, category / condition / price filters, page 1 and 2 | 0.8-1.5 ms | 0.8-1.5 ms |

Other reads, unchanged and all served by an index: favorites, my listings, a seller's listings and profile 1.0-2.7 ms;
inbox and orders (also for an account with ~3,000 conversations and ~1,500 orders) 1.8-2.3 ms; listing, conversation,
messages and order pages 0.9-1.6 ms; ratings, blocks, the report and dispute queues and the moderation log 0.9-2.1 ms.
`GET /categories` reads one index entry per ACTIVE listing of the campus: 20-29 ms at 110,000 of them, in
proportion for smaller campuses. The admin-only staff list (2.5 ms) and user search (13 ms) read the whole `users` table.

How text search stays quick (see `ListingBrowse.textSearch`):
- Each relevance class is first looked for among the next 1,000 listings of the campus in order (newest first, or by
  price); a text that matches often fills the page long before that. Only when it doesn't are the class's matches
  fetched through an index: `listings_search_idx` / `listings_title_search_idx` for full-text queries, and for
  substring queries the two `search_grams` indexes, which hold every one- and two-character piece of each ACTIVE
  listing's title and description per campus. A search is one to seven short statements instead of one scan of the
  campus (which also kept three CPU cores busy for those 60 ms).
- The weak spot is a text that is rare among the newest listings combined with a filter: the walk has to read until
  it has seen 1,000 listings that pass the filter (the 7-11 ms rows above).
- Costs: the gram indexes are 75 MB next to a 211 MB table (26 MB for the full-text index); indexing one listing takes
  about 0.25 ms for a typical description and 4.5 ms for a 4,000-character one. The migration took 75 s here
  (185,000 ACTIVE listings on all campuses) and blocks listing writes while it runs.

## Chat
- Cursors are opaque; messages come newest-first; conversations ordered by last activity.
- Send: body trimmed, 1..2000 chars; errors in `fields.body` (or `fields.initialMessage` on start).
- **Retrying a send:** put a fresh id (a UUID) in `SendMessageRequest.clientId` for every message and repeat it on every
  retry of that message. The server keeps one message per (sender, `clientId`) and answers a repeat with the stored
  one, so a send whose response was lost (timeout, app killed) can always be retried. `Message.clientId` comes back in
  the response, in the history and on the socket: match it against what is still pending locally. Messages from
  clients that send no id have `clientId` null and are never de-duplicated. Migration `V6_chat_1`.
- Blocked either way: history readable, send → 403 `BLOCKED`. Sending to a deleted user → 409.
- WebSocket: `GET /api/v1/ws?token=<accessToken>`. Bad/expired token → accepted then closed with VIOLATED_POLICY (1008): refresh the access token, then reconnect.
  An **open** socket is also closed with 1008 when its access token expires (so about every 15 minutes) and within ~30 s
  of the account being suspended or deleted. Same handling: refresh, reconnect (a suspended account's refresh fails with
  403 `ACCOUNT_SUSPENDED`), and re-fetch via REST anything that may have arrived in between. To avoid the gap, open a
  second socket with a refreshed token shortly before the first one's token expires, wait for a `Pong` on it (the
  socket is subscribed by then), and close the old one; both deliver for a moment, so de-duplicate by message id
  (the app does this, see docs/CLIENT.md).
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
- `GET /mod/staff`, `GET /mod/users/search?q=` and `PUT /mod/users/{id}/role` are **ADMIN only** (moderators get 403). Hide the entry points for non-admins; the app shows "Staff" in the profile tab's Moderation group and in the moderation screens' app bars for admins only.
- Admins are never created or altered through the API: they come from `ADMIN_EMAILS` on verification. The role endpoint can't target an admin (403), can't be pointed at yourself (400) and can't assign `ADMIN` (400). Promote/demote `USER` ↔ `MODERATOR` only.
- Role changes take effect on the target's next request (no re-login needed); the audit row (`moderator_id` = the admin, note `"<old> -> <new>"`) shows up in `/mod/log`.
- `StaffMember.email` is returned to admins only; never show it elsewhere.
- Search needs >= 2 characters (shorter returns an empty list, so debounce or search on submit) and returns at most 20 rows.

## Demo data (after `./gradlew :server:seed`)
Password `password123`: `admin@state.edu` (ADMIN), `moderator@state.edu` (MODERATOR), sellers `maya@ jordan@ priya@ sofia@state.edu`, buyers `liam@ noah@ tyler@state.edu`; `dev@ ava@ ethan@ zoe@tech.edu` on the second campus.
- 44 listings posted over the last few weeks (newest minutes ago), each with 1-3 real product photos bundled in
  `server/src/main/resources/seed/photos/` (sources and licences in `CREDITS.md` there). They are left out of the server jar; the distribution and the Docker image carry them in
  `lib/seed-photos.jar`, which only `bin/seed` loads. Seed a container with
  `docker compose --profile server run --rm --entrypoint /app/bin/seed server` (add `--reset` to wipe first). A
  listing whose file is missing
  gets a generated placeholder and the seed names it. Eight users have illustrated avatars (shapes on Golden Hour
  gradients, never photos of people); the rest show the app's initials.
- Orders in every state but awaiting-payment: completed (rated, some 3-4 stars), paid and awaiting handoff (sofia@ buying
  jordan@'s monitor, ethan@ buying ava@'s tent), refunded, cancelled, and one disputed (zoe@tech.edu buying from
  ava@tech.edu), so the moderators' dispute queue isn't empty. Paid orders were paid hours before seeding (their 72 h
  hold runs from then).
- 14 conversations with realistic timing; four have unread messages (for maya@, liam@, dev@ and ava@). The open reports
  all concern tyler@, who asks buyers to pay off-app.
- Ids derive from stable keys, so re-running is a no-op (after changing listing keys, reseed with `--reset`). Seed photo
  files are named `seed-<photo id>-<content hash>.jpg`, so a replaced photo gets a new URL despite the immutable cache
  headers; the seed makes their variants too (`…-t.jpg`; the bundled photos are already display-sized). Unreferenced
  `seed-*` files in `UPLOADS_DIR` are deleted at the end of each run (other uploads are left alone).
