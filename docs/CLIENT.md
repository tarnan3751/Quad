# Quad: client guide

How the app (`shared/` + platform shells) is put together, and how to build features on it.
Read `docs/CONTRACT.md` first: endpoints, wire types (`core/`) and the product scope live there.

## TL;DR for feature engineers

1. Find your package in the ownership table below. Edit only files in it.
2. Your destinations are defined in `XxxRoutes.kt` and registered in `XxxGraph.kt`.
3. One `ViewModel` per screen. Inject `ApiClient` (and `SessionManager`, `ChatSocket`, `ExternalLinks`, `UserMessages` as needed) through the constructor. Register it in your feature's Koin module, which lives in your `XxxGraph.kt`.
4. Single objects use `UiState<T>` and `UiStateContent`. Lists use `Paginator` and `PagedLazyColumn` (`PagedListingGrid` for listing tiles). Forms follow the auth feature (`feature/auth/AuthViewModels.kt`).
5. Build with components from `ui/components` and the theme in `ui/theme`. Don't hard-code colours. Before writing a row, card, skeleton, dialog, icon or date format, look for it there first; if a second feature needs a piece of yours, move it there rather than copying it.
6. Verify: `./gradlew :shared:jvmTest :desktopApp:compileKotlin :androidApp:assembleDebug --console=plain`, then click through in `./gradlew :desktopApp:run`.

## Running

| What | How |
|---|---|
| Server | `./gradlew :server:run` → http://localhost:8080 (devMode: verification codes are shown in the app) |
| Desktop harness | `./gradlew :desktopApp:run`. Server from `CAMPUS_MARKET_API` (default `http://localhost:8080`). Always a debug build. |
| Android | `./gradlew :androidApp:installDebug`. The emulator reaches the host at `http://10.0.2.2:8080` (the default). |
| Android, release code | `./gradlew :androidApp:installBenchmark`: the `benchmark` build type is `release` (R8, resource shrinking, not debuggable) signed with the local debug key and allowed to reach the HTTP dev server. Use it to measure performance (debug Compose is several times slower) and to check R8 keep rules before a release. Never distribute it. |
| Android, store build | `./gradlew :androidApp:assembleRelease` / `:androidApp:bundleRelease` (AAB). R8 full mode + resource shrinking, English resources only, HTTPS only. Unsigned until an upload key is set up (docs/RELEASE.md); the R8 mapping is in `androidApp/build/outputs/mapping/release/`. |
| iOS | Open `iosApp/iosApp.xcodeproj` in Xcode and run the `iosApp` scheme, or `xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -destination 'platform=iOS Simulator,name=iPhone 17' build` then `xcrun simctl install booted <…/Debug-iphonesimulator/Quad.app>`. The simulator uses `http://localhost:8080`. Keep code signing on (the simulator default, no team needed): a `CODE_SIGNING_ALLOWED=NO` build has no Keychain access, so the session is lost on every relaunch (debug builds print `Quad token store: …`). |
| Other server (e.g. phone on LAN) | Debug builds only: Welcome screen → "Dev server · …" (or Settings → API server). The override is persisted. |
| Deep links | Android: `adb shell am start -a android.intent.action.VIEW -d "campusmarket://orders/<id>"`. Desktop: `./gradlew :desktopApp:run --args="campusmarket://orders/<id>"`. iOS: `xcrun simctl openurl booted campusmarket://payouts`. |
| iOS UI tests | `xcodebuild test -project iosApp/iosApp.xcodeproj -scheme iosApp -destination 'platform=iOS Simulator,name=iPhone 17' -only-testing:iosAppUITests/QuadFlowTests` (needs the dev server). `iosAppUITests/QuadFlowTests.swift` finds Compose elements by their accessibility labels (fields are `textViews`). |
| Drive the iOS app by hand | `scripts/ios-drive.sh start`, then `scripts/ios-drive.sh tree` / `tap "Sign in"` / `type …` / `back` / `@safari tap "Pay $12"` (commands in `iosAppUITests/QuadDriver.swift`; `type` is fast `typeText`, `keys` taps the on-screen keys one by one). It runs a UI test that executes commands from a directory, so taps, typing, swipes, rotation and other apps (Safari, Settings) can be scripted from a shell; screenshots with `xcrun simctl io booted screenshot`. |

Photo / avatar URLs from the server are absolute and built from the server's `PUBLIC_BASE_URL`, which defaults to `http://localhost:8080` and can't be reached from the emulator. `ServerConfig.resolve()` rewrites loopback origins to the app's base URL. `RemoteImage` and `ExternalLinks` do this for you, so never load server URLs any other way.

Photos come in sizes (docs/SERVER_NOTES.md "Photos"); load the smallest that fills the slot, through the helpers in `ui/components/Images.kt`, which fall back to the original when a variant is missing: `listing.listImageUrl` for grid tiles and list rows (inbox, orders, moderation), `user.avatarImageUrl` for avatars, `photo.screenImageUrl` for the listing photo pager, `photo.smallImageUrl` for the editor's photo strip, and `photo.url` (the original) only for the zoomable full-screen viewer. A grid tile's thumbnail is about a ninth of the original's bytes.

## Package layout (`shared/src/commonMain/kotlin/com/campusmarket/app/`)

```
App.kt                     root composable (theme, Coil, URL resolver, AppNavigation)
di/Koin.kt                 coreModule + list of feature modules + initKoin
data/                      ApiClient, ApiError, SessionManager, TokenStore, ServerConfig, ChatSocket, HttpClient config
platform/                  expect/actual + platform glue: Platform.kt, ExternalLinks, DeepLinks, ImagePicker, ImageCache, NetworkMonitor
navigation/AppNavigation.kt  auth gate, bottom bar (TopLevelTab), NavHosts, deep-link routing, snackbars, LocalCurrentUser
ui/theme/Theme.kt          CampusMarketTheme (Quad's "Golden Hour"), CampusTheme.colors / gradients, GoldenHour swatches, Spacing
ui/components/             reusable components (catalogue below)
ui/state/                  UiState, load(), EventQueue, Paginator/PagedState, UserMessages
ui/TimeFormat.kt           every date and time (12/24-hour clock, "3h ago", "Yesterday", countdowns)
ui/Format.kt, Labels.kt, Validation.kt   money / rating formatting, enum labels, form validation
feature/auth/              (foundation) welcome, register, verify, login: the reference implementation
feature/browse/  feature/listing/        → A
feature/chat/    feature/orders/         → B
feature/profile/ feature/moderation/     → C
```
Platform code: `shared/src/{androidMain,iosMain,jvmMain}/…/platform/Platform.*.kt`, shells in `androidApp/`, `iosApp/`, `desktopApp/`.

## Ownership

| Owner | Edits | Notes |
|---|---|---|
| A | `feature/browse/**`, `feature/listing/**` | browse/search/filters, listing detail, create/edit listing + photos, favorites, my listings |
| B | `feature/chat/**`, `feature/orders/**` | inbox, conversation, buy flow, orders list/detail, confirm/cancel, rating, payouts |
| C | `feature/profile/**`, `feature/moderation/**` | profile, edit profile, public profile, settings, account deletion, blocked users, `ReportSheet`, `BlockUserDialog`, moderation queue/detail/log |
| Foundation | everything else in `shared/`, the shells, `docs/CLIENT.md` | Ask before changing shared code. Feature-local helpers stay in your package. |

Shared code you may need to touch in passing (a new component, a new `ApiClient` method if the contract grows): keep the change additive and minimal, and mention it in your report.

New routes: add them to your own `XxxRoutes.kt` and register them in your own `XxxGraph.kt`. There's no central route file to fight over.

## Architecture

```
Composable screen ── koinViewModel() ──▶ ViewModel ──▶ ApiClient ──▶ Ktor HttpClient ──▶ server
      ▲   collectAsStateWithLifecycle        │  StateFlow<State>     │ tokens
      └──────────── StateFlow / events ──────┘                       ▼
                                                     TokenStore ◀── SessionManager (StateFlow<SessionState>)
```

- **DI: Koin 4.** Core singletons (`coreModule` in `di/Koin.kt`): `ServerConfig`, `TokenStore`, `HttpClient`, `ApiClient`, `SessionManager`, `ChatSocket`, `UserMessages`, `ExternalLinks`, `AppEnvironment`, plus the platform `PlatformUrlLauncher` and `NetworkMonitor`. `initKoin` builds the session (a Keystore / Keychain read) and the HTTP client on a background thread at launch, so the first frame doesn't wait for them. Each feature registers its view models in its own module (`browseModule`, `listingModule`, …), which is already listed in `featureModules`.
- **View models:** `androidx.lifecycle.ViewModel` (multiplatform), scoped to the navigation back-stack entry by `koinViewModel()`. Expose immutable state as `StateFlow` and take actions as plain functions.
- **Route arguments → view model:** pass the route object.
  ```kotlin
  // XxxGraph.kt
  composable<ListingDetailRoute> { entry -> ListingDetailScreen(entry.toRoute<ListingDetailRoute>(), nav) }
  val listingModule = module {
      viewModel { params -> ListingDetailViewModel(route = params.get(), api = get(), messages = get()) }
      viewModelOf(::SellViewModel)                 // no route args
  }
  // Screen
  val vm = koinViewModel<ListingDetailViewModel> { parametersOf(route) }
  ```
- **Screens** take route args plus `nav: NavController` and navigate directly (`nav.navigate(ConversationRoute(id))`, `nav.popBackStack()`). Each screen owns its `Scaffold` + `AppTopBar`. The signed-in shell already applies the bottom-bar padding and consumes those insets, and keeps every screen clear of a display cutout or side navigation bar (landscape), so screens only deal with top and bottom insets. The signed-out shell leaves the sides alone, because the Welcome hero runs to the screen edge; the auth forms (`AuthFormScaffold`) keep clear of the cutout themselves.
- **Current user:** `LocalCurrentUser.current` in composables, `sessionManager.state` / `currentUser` in view models. Use `user.isModerator` for moderator-only UI.

### State patterns (`ui/state/`)

| Need | Use |
|---|---|
| Load one thing (listing, order, profile) | `MutableStateFlow<UiState<T>>(UiState.Loading)` + `load(state) { api.listing(id) }`; render with `UiStateContent(state, onRetry = vm::reload) { … }` (pass `loading = { … }` with a skeleton when the layout is known). Pull-to-refresh: `load(state, keepContent = true) { … }`. Optimistic edits: `state.updateContent { it.copy(favorited = true) }`. |
| Cursor list (`Page<T>`) | `val items = Paginator(viewModelScope, messages = messages, key = { it.id }) { cursor, limit -> api.xxx(cursor = cursor, limit = limit) }` (`messages`: the `UserMessages` where a refresh that fails while items are on screen says why) and render with `PagedLazyColumn(vm.items, key = { it.id }) { … }` (listing tiles: `PagedListingGrid`). It starts the first load (skeleton rows / tile skeletons; override with `loading = { … }`), handles pull-to-refresh, infinite scroll, retry footer and empty state. New query: `reset()`. Local edits: `items.update { list -> … }`. |
| Form | Immutable `XxxState(fields…, fieldErrors: Map<String,String>, formError: String?, submitting)`. Validate locally with `Validate.*` + `fieldErrorsOf(...)`, then call the API and map failures with `e.toFormErrors(codeToField)`. Show errors with `AppTextField(error = state.fieldErrors["title"])` and `InlineError(state.formError)`. Flag a field as soon as the user leaves it with `Modifier.onFocusLost(vm::checkTitle)` (skip empty fields). Pass `loading = state.submitting` to the submit button and return early from `submit()` while submitting (double taps). See `feature/auth/AuthViewModels.kt`. Lay the fields out in a `FormColumn` (not `Column(Modifier.imePadding().verticalScroll(…))`) and mark the submit button with `Modifier.formSubmit()`: with the keyboard up it keeps the focused field and the submit button on screen together. |
| Refresh when the user comes back (from another screen or the browser) | Screen: `OnResume(vm, vm::onResume)`. View model: `private val resumes = ResumeGate()` and `fun onResume() { if (resumes.isReturn()) refresh() }` (the first resume is the initial load, which is already running). |
| One-off event (navigate after save, open URL) | `val events = EventQueue<MyEvent>()`, `events.send(...)` in the VM, `CollectEvents(vm.events.flow) { … }` in the screen. |
| Snackbar / toast | Inject `UserMessages`: `messages.show("Listing deleted")`, `messages.show("Removed", "Undo") { … }` or `messages.showError(e)` / `showError("Couldn't save", "Retry") { … }` (errors get an alert icon, stay longer and can be closed). It survives navigation. Keep the text short and plain. On a screen with a pinned bottom bar (action bar, chat composer) put `Modifier.keepSnackbarsAbove()` (`navigation/`) on it so snackbars show above it, not over it. |
| Inbox tab badge | `UnreadConversations` (Koin single, `feature/chat/`): the shell collects `count` (conversations with unread messages) for the Inbox tab; it follows the chat socket while collected and re-syncs on resume. |
| Scroll to top when the current tab is tapped again | `OnTabReselected(TopLevelTab.Browse) { gridState.animateScrollToItem(0) }` in the tab's root screen (Browse, Inbox and Profile do this). |

**Text fields in sheets and dialogs.** A `ModalBottomSheet` / dialog is its own composition. A field in one must not take
its text straight from a `State` that a coroutine fills in the screen's composition (`val state by
flow.collectAsStateWithLifecycle()` next to the `ModalBottomSheet(...)` call, read inside its content): on iOS the value
then reaches the field late, the field snaps back to the older text, and fast typing (a hardware keyboard, XCUITest
`typeText`) loses and reorders characters. This was the report sheet's bug. Do one of these instead:
- keep the text in `remember { mutableStateOf(...) }` and push each change to the view model / controller
  (`ReportSheet`, `ModerationActionDialog`, `ServerSettingsDialog`); or
- collect the flow inside the sheet's / dialog's content (`SuspendedAccountDialog`); or
- read the state in the screen and pass plain values down (the filter sheet's `FilterDraft`, the delete-account
  dialog): the screen recomposes and hands the sheet the new value.

Fields on ordinary screens are fine with the view-model round trip.

## Calling the API

`ApiClient` (`data/ApiClient.kt`) has one typed `suspend` function per contract endpoint, named after the resource:
`me, updateMe, deleteMe, user, blockUser, unblockUser, myBlocks, uploadPhoto, createListing, listings(ListingQuery), listing, updateListing, deleteListing, favorite / unfavorite / setFavorite, myFavorites, myListings(status), userListings, categories, startConversation, conversations, conversation, messages, sendMessage, markConversationRead, payoutAccount, startPayoutOnboarding, createOrder, orders(role), order, confirmOrder, cancelOrder, openDispute, withdrawDispute, rateOrder, userRatings, report, modReports(status), modReport, resolveReport, suspendUser, unsuspendUser, removeListing, restoreListing, moderationLog, modDisputes(status), modDispute, resolveDispute, modStaff, searchUsers, setUserRole, deleteAccountWithCredentials, health`.
Auth calls (`register / verifyEmail / resendCode / login / logout`) are used through `SessionManager`.

- **Errors:** every function returns the body or throws `ApiError` (never anything else besides cancellation). Fields: `code` (`ErrorCodes.*` or `ApiError.NETWORK / UNEXPECTED / SESSION_EXPIRED`), `message` (user-presentable), `fields` (per-field validation, `error.field("title")`), `status`. Catch `ApiError`, rethrow `CancellationException` (the `load()` and `Paginator` helpers already do this).
  ```kotlin
  try { api.createOrder(listingId) }
  catch (e: ApiError) { when (e.code) { ErrorCodes.PAYOUTS_NOT_ENABLED -> …; ErrorCodes.LISTING_UNAVAILABLE -> …; else -> messages.showError(e) } }
  ```
- **Threads:** call `ApiClient` from the main thread (view models do). Each call, including a token refresh and the JSON decoding, runs on the HTTP engine's I/O dispatcher and resumes on the caller's.
- **Auth is automatic:** bearer header, proactive refresh 30 s before expiry, refresh on 401 (single-flight: concurrent calls share one refresh) and one retry. If refresh is rejected or the account is suspended, the session is cleared, the app drops to the Welcome screen (which shows the reason), and the call throws `SESSION_EXPIRED`. Don't handle that case yourself.
- **Endpoints returning `OkResponse` return `Unit`.**
- **Paging:** every list function takes `cursor` and `limit` (defaults: server's). `nextCursor == null` means the end. `Paginator` handles this.
- **Photos:** `val picker = rememberImagePicker(maxItems = n) { images -> vm.upload(images) }` and then `picker.launch()`. Each `PickedImage` is already JPEG (max 2048 px, which converts iPhone HEIC). Upload each with `api.uploadPhoto(img.bytes, img.fileName, img.mimeType)` → `Photo(id, url)`, then send the ids in `CreateListingRequest.photoIds` / `UpdateProfileRequest.avatarPhotoId`. No permissions needed on any platform.
- **External pages (Stripe):** inject `ExternalLinks` and call `links.open(order.checkoutUrl!!)` / `links.open(api.startPayoutOnboarding().url)`. The browser returns to the app via `campusmarket://orders/{id}` / `campusmarket://payouts`, and the shell navigates to `OrderDetailRoute` / `PayoutsRoute` (`launchSingleTop`). Those screens should reload on resume (`LifecycleResumeEffect`), because the state changed while the user was in the browser.
- **Session:** `SessionManager.state: StateFlow<SessionState>` (`SignedOut` / `SignedIn(user)`). After `PATCH /me` call `session.updateUser(result)`. After payouts onboarding call `session.refreshUser()` so `payoutsEnabled` updates. `session.signOut()`, `session.deleteAccount()` (throws 409 `CONFLICT` while orders are PAID).

### Chat live updates (`data/ChatSocket.kt`)

- `chatSocket.events: SharedFlow<ChatEvent>` is hot and connects on demand: the websocket opens when the first collector subscribes and closes 5 s after the last one leaves. Collect it in the inbox / conversation view model's `viewModelScope`.
- The server closes a socket when its access token expires (every ~15 min). A minute before that, `ChatSocket` refreshes the token, opens a second socket, waits for it to answer a ping and then closes the old one: events keep flowing, `status` stays `Connected`, and a message both sockets delivered is emitted once. If that isn't possible (offline, the device slept) the server's policy-violation close makes it refresh and reconnect at once, without a backoff wait.
- Otherwise it reconnects with backoff (1 s → 30 s), and at once when the platform `NetworkMonitor` (Android `ConnectivityManager`, iOS `NWPathMonitor`) says a network is back. `chatSocket.status` exposes `Idle / Connecting / Connected / Reconnecting` for a "Reconnecting…" hint. The websocket itself sits behind `ChatConnector` / `ChatConnection`, which tests script (`ChatSocketRenewalTest`).
- **Unsent messages** stay in the conversation marked "Not sent", with Retry and Delete under the bubble, and the inbox row of that conversation says "Not sent: …" until they are sent or deleted (`InboxViewModel.notSent`). They and the composer draft are kept in `ChatOutbox` (`feature/chat/`, Koin single, one user's at a time), which also stores them on the device, so they survive leaving the conversation and the app being killed. Message text is private: it goes through a second `SecretStorage` slot (`OutboxSecrets` in `di/Koin.kt`; Keystore-encrypted prefs / Keychain, plain prefs only on the desktop harness), written a moment after the last change. `ChatOutbox` is a `SignOutListener`: `SessionManager.signOut()`, `deleteAccount()` and `deleteAccountWithCredentials()` clear it; a session the server ended keeps it for the same user's next sign-in, and another user signing in never sees it and replaces it. Bind anything else that keeps a user's private data on the device to `SignOutListener` the same way.
- **Sends are idempotent.** Every message gets a client id when it is typed (`PendingMessage.localId`, a UUID), sent as `SendMessageRequest.clientId` on the first try and on every retry, so retrying a send whose response was lost returns the message the server already has instead of posting it twice. A server message carrying a pending bubble's id (REST response, socket echo, history, catch-up) retires the bubble. A send cut off by leaving the chat or by the process dying comes back as "Not sent" unless the history has its id.
- **Send with REST** (`api.sendMessage(conversationId, body, clientId)`) to get the created `Message` and typed errors (`BLOCKED`). The server echoes it as `MessageCreated` on the socket too, so **de-duplicate by `message.id`**. `api.markConversationRead(id)` marks a conversation read. `chatSocket.reconnectNow()` skips the backoff wait.
- **Unread total (Inbox tab badge):** `UnreadConversations` (`feature/chat/`, Koin single) exposes `count: StateFlow<Int>`, the number of conversations with unread messages (0 when signed out). Collect it where the badge is drawn: `koinInject<UnreadConversations>().count.collectAsStateWithLifecycle()`. While collected it syncs the first page of `GET /conversations`, follows socket events (polling while the socket is down; collecting keeps the socket open, stopping 5 s after the last collector) and takes what the inbox / conversation screens report, so badge and list agree. `refresh()` re-syncs on demand.
- **Dates and times:** every screen formats them with `rememberTimeFormat()` (`ui/TimeFormat.kt`), which follows the device's 12/24-hour setting (Android `DateFormat.is24HourFormat`, iOS `j` skeleton, desktop locale) and words dates the way people say them ("Yesterday", "Thu, Nov 12 at 9:24 AM", "in 2 days, 4 hours", "3h ago", "Joined Mar 2025"). On Android the setting is re-read on every resume (it's changed in the Settings app). Don't hand-format dates or times. Tests pin it with `LocalUse24HourClock` or construct `TimeFormat(use24h, tz, now)` directly.

## Navigation

Type-safe navigation-compose (2.9). Routes are `@Serializable` objects/classes in each feature's `XxxRoutes.kt`. Each feature registers them in `fun NavGraphBuilder.xxxGraph(nav: NavController)` (in `XxxGraph.kt`), which `navigation/AppNavigation.kt` calls. Signed-out and signed-in sides are separate NavHosts behind an auth gate, so signing out discards the signed-in back stack. The bottom bar shows only on the four tab roots; Inbox carries the unread badge, and tapping the current tab again emits on `LocalTabReselected` (`OnTabReselected`). When the window is short (`isCompactHeight()`, under 480 dp: a phone in landscape) the tabs move to a rail on the start edge (`AppNavigationRail`) and Browse drops its title bar, putting Favorites in the search row. Use `nav.navigateToTab(TopLevelTab.Inbox)` to jump to a tab.

| Route | Args | Owner | Screen file | Notes |
|---|---|---|---|---|
| `WelcomeRoute` | | Foundation | `feature/auth/AuthScreens.kt` | Signed-out start. Debug: server override. A phone in landscape gets the hero beside the buttons. |
| `RegisterRoute` | | Foundation | 〃 | |
| `VerifyEmailRoute` | `email`, `devCode?` | Foundation | 〃 | Shows the dev code with "Use code". |
| `LoginRoute` | `email?` | Foundation | 〃 | `EMAIL_NOT_VERIFIED` → resend + verify. |
| `BrowseRoute` | | A | `feature/browse/BrowseScreen.kt` | **Tab**, signed-in start destination |
| `SellRoute` | | A | `feature/listing/ListingEditorScreens.kt` | **Tab**: create listing |
| `EditListingRoute` | `listingId` | A | 〃 | Asks before discarding unsaved changes (`BackHandler` from `ui-backhandler`) |
| `ListingDetailRoute` | `listingId` | A | `feature/listing/ListingDetailScreen.kt` | Short window (`isCompactHeight()`): no title bar, the photo fills the left half with back / favorite / more floating on it, and the action bar is pinned under the details only. Links to `MessageSellerRoute`, `CheckoutRoute`, `UserProfileRoute`, `ReportSheet`. No Buy / Message when a block stands between buyer and seller (`ListingDetail.blocked`, `seller.blockedByMe`) |
| `FavoritesRoute` | | A | `feature/listing/MyListingsScreens.kt` | |
| `MyListingsRoute` | | A | 〃 | |
| `InboxRoute` | | B | `feature/chat/InboxScreen.kt` | **Tab** |
| `ConversationRoute` | `conversationId` | B | `feature/chat/ConversationScreen.kt` | |
| `MessageSellerRoute` | `listingId` | B | 〃 | `startConversation` then replace itself with `ConversationRoute` |
| `CheckoutRoute` | `listingId` | B | `feature/orders/OrderScreens.kt` | Buy flow |
| `OrdersRoute` | `role?` (`"buyer"`/`"seller"`) | B | 〃 | |
| `OrderDetailRoute` | `orderId`, `fromCheckout` (default false) | B | 〃 | Deep link `campusmarket://orders/{id}`. `fromCheckout`: back from Stripe Checkout with the order unpaid, it re-checks a few times ("Confirming your payment…") before saying the payment didn't come through |
| `RateOrderRoute` | `orderId`, `stars` (default 0) | B | 〃 | `stars` pre-selects the rating tapped on the order screen |
| `ReportProblemRoute` | `orderId` | B | `feature/orders/DisputeScreens.kt` | Buyer of a PAID order opens a dispute (`openDispute`); payment goes on hold. Opened from `OrderDetailRoute`, which also offers `withdrawDispute` |
| `PayoutsRoute` | | B | 〃 | Deep link `campusmarket://payouts` |
| `ProfileRoute` | | C | `feature/profile/ProfileScreen.kt` | **Tab**: header + stats (active / sold from `myListings`, rating), grouped menus (buying & selling, moderation for staff, account, safety, legal), sign out, version |
| `EditProfileRoute` | | C | `feature/profile/ProfileScreens.kt` | |
| `UserProfileRoute` | `userId`, `showRatings?` | C | 〃 | Listings / ratings (star breakdown) tabs; Message (pick one of their listings → `MessageSellerRoute`), Report, Block |
| `SettingsRoute` | | C | 〃 | Email / campus, blocked users, legal links, version, debug server, sign out, delete account |
| `BlockedUsersRoute` | | C | 〃 | |
| `ModerationQueueRoute` | | C | `feature/moderation/ModerationScreens.kt` | "Reports". Moderators only (server returns 403 otherwise). Status tabs + kind filter (client-side) |
| `ReportDetailRoute` | `reportId` | C | 〃 | |
| `ModerationLogRoute` | | C | 〃 | |
| `DisputesRoute` | | C | `feature/moderation/ModerationDisputeScreens.kt` | Moderators only. Open / Closed tabs (`modDisputes`); opened from the profile tab's Moderation group and the other moderation screens' top bars |
| `DisputeDetailRoute` | `orderId` | C | 〃 | Both parties, listing, amounts, reason; refund the buyer or release to the seller (`modDispute`, `resolveDispute`, note required) |
| `StaffRoute` | | C | `feature/moderation/StaffScreen.kt` | Admins only: list staff, search users as you type, promote / demote moderators (`modStaff`, `searchUsers`, `setUserRole`) |

Shared non-route UI owned by C with **final signatures** (A and B call them now, C fills them in):
`ReportSheet(targetType, targetId, onDismiss, onReported)` in `feature/moderation/ReportSheet.kt` and `BlockUserDialog(user, onDismiss, onDone)` in `feature/profile/BlockUserDialog.kt`.
The sheet shows its own "Report sent" confirmation, so callers don't need a snackbar. The server ignores a repeat report
while the first is open, so the sheet remembers (for the app session, `ReportedTargets`) what was reported and reopens
on "You already reported this" instead of the form.

Route args are primitives/strings. Use enum *names* as strings if you need an enum (navigation's KMP enum support is uneven).

## Components (`ui/components/`)

| Component | Use |
|---|---|
| `PrimaryButton`, `SecondaryButton`, `DestructiveButton` | 48 dp pill buttons with `loading` spinner + optional icon. Primary = Golden Hour gradient with white label (one per screen: the main call to action); Secondary = ghost pill with a primary-colour ring; Destructive = solid error red |
| `FilterChip(selected, onClick, label, …)` | Quad's filter chip: Material's `FilterChip` API, import `com.campusmarket.app.ui.components.FilterChip` instead of the Material one. Pill; gradient + white label when selected |
| `AppTextField` | Outlined field with `error` (field error), `supportingText`, `maxLength` counter, prefix/icons, `contentType` (autofill hint, e.g. `ContentType.PersonFullName`) |
| `EmailField`, `PasswordField` (eye toggle, `newPassword` for sign-up) | Specialised fields with autofill hints. The keyboard's Next / Done keeps its default (next field / hide keyboard) after your `onImeAction`. Prices: `PriceInput` in `feature/listing`; text ↔ cents via `Format.parsePriceCents` / `Format.priceInput` |
| `CodeField(value, onValueChange, length)` | One-time code in separate boxes (digits only, number keyboard, one-time-code autofill) |
| `Modifier.onFocusLost { }` | Run a check when focus leaves a field (inline validation) |
| `InlineError(message)`, `InfoBanner(message, icon)` | Form-level error banner (alert icon, announced by screen readers); the same banner in neutral colours for notices that aren't failures |
| `LoadingState`, `ErrorState(ApiError, onRetry)`, `EmptyState(title, message, icon, actionLabel, onAction, secondaryActionLabel, onSecondaryAction)` | Full-area states. Empty / error states scroll instead of clipping in short spaces; offline errors get a cloud icon and neutral colours. An empty state should say what goes here and offer the one action that fills it |
| `ListSkeleton(leading = SkeletonLeading.Avatar/Thumbnail/None)`, `ListRowSkeletons(rows)` (plain column, for sheets and scrolling screens), `ListRowSkeleton`, `ListingTileSkeleton`, `SkeletonBox`, `SkeletonLine`, `ShimmerGroup` | Shimmering placeholders in the shape of the content. Prefer them to a spinner whenever the layout is known (e.g. `UiStateContent(…, loading = { MyDetailSkeleton() })`) |
| `UiStateContent(state, onRetry, loading = { … }) { value -> }` | Loading/error/content switch for `UiState`. Its `modifier` is for the loading and error states only: the content lambda pads itself (`Modifier.padding(padding)` on what it draws) |
| `CollectEvents(flow) { }` | Consume `EventQueue` events |
| `PagedLazyColumn` | Infinite list for a `Paginator` (skeleton first load, pull-to-refresh, footer, empty) |
| `PagedListingGrid` | Paged grid for a `Paginator<ListingSummary>` whose header (categories, sort) stays on screen through skeleton loading, a failed first load and the empty state; footer shows loading more / retry / end of list. As many columns as fit at `minTileWidth` (160 dp), so landscape and tablets get more |
| `FormColumn { … }`, `Modifier.formSubmit()` | The scrolling column of a form: shrinks above the keyboard and scrolls so the focused field and the marked submit button are both visible (see "Form" above) |
| `OnResume(key) { }` | Runs on every resume of the screen (see "Refresh when the user comes back") |
| `RemoteImage(url, …)` | Coil image: shimmers while loading, `placeholderIcon` (a photo icon) when missing or failed; resolves dev URLs. Pass the size that fits the slot (`listing.listImageUrl`, `user.avatarImageUrl`: see the photo sizes note under "Running") |
| `Avatar(url, name, size, contentDescription = name)` | Round photo over initials on a Golden Hour colour picked from the name (initials show while loading and if the photo fails). Pass `contentDescription = null` when the name is printed next to it |
| `CountBadge(count)` | Berry count pill ("3", "99+"), or a dot for 0 |
| `PriceText(cents)` | Price in the price colour (berry on light, amber on dark), weight 800 |
| `RatingStars(rating)`, `RatingSummary(avg, count)`, `RatingInput(stars, onChange)` | Display (fractional; read as "Rated 4.8 out of 5, 12 ratings", the phrase `ratingSpoken(avg, count)` gives for row summaries) and input (48 dp stars) |
| `StatusChip(text, ChipTone)`, `ListingStatusChip`, `OrderStatusChip`, `ReportStatusChip`, `ConditionChip` | Status pills (consistent colours) |
| `ListingTile(listing, onClick, onToggleFavorite?)` | Grid tile: square cropped photo, price, title clamped to 2 lines with both lines reserved, "condition · 2h ago", status badge, heart. Every tile in a row has the same height. Read by screen readers as one element ("title, price, condition, age, status"); the heart is separate |
| `ListingImage` | Listing photo with a pulse while loading, a photo outline when missing and a crossed-out photo when it fails. `placeholderUrl`: a smaller copy already on screen (the tile's thumbnail under the pager photo, the pager photo under the zoomable original) shows at once from memory while the larger one loads (`rememberImageModel`) |
| `FavoriteButton` | 48 dp heart toggle (pops and gives haptic feedback when it fills) |
| `ListingIcons`, `ListingCategory.icon`, `IconBadge` | Line icons for categories and listing facts; an icon in a tinted circle |
| `UserRow(user, onClick, supporting, trailing)` | Avatar + name + rating (seller card, order parties, blocked users) |
| `AppTopBar(title, onBack, actions, scrollBehavior)`, `BackButton` | Chrome. Titles are headings for screen readers |
| `AppNavigationBar { AppNavigationBarItem(selected, onClick, icon, label, badgeCount, badgeDot, badgeDescription) }` | The bottom tab bar (used by `AppNavigation`); the selected tab sits in a gradient pill; optional count badge |
| `AppNavigationRail { AppNavigationRailItem(…) }`, `isCompactHeight()` | The same tabs as a rail at the start edge, which the shell uses when the window is under 480 dp tall (a phone in landscape). Screens with a lot of chrome can fold it away with the same check |
| `AppSnackbar(data)`, `AppSnackbarVisuals` | The shell's snackbar (rounded, inverse colours, alert icon for errors). `UserMessages` already uses it |
| `QuadIcons.*` | Filled icons missing from `material-icons-core` (chat bubbles, visibility, image, cloud off, error, flag, block, shield, tag, receipt, wallet, gavel, history, group, camera, school, document, verified, undo, open in new, tune, …). Add new ones here |
| `QuadMark(size, color)`, `QuadAppIcon(size)`, `QuadWordmark(color, fontSize)` | Brand: the "Sun on the Quad" mark (see Theme), the app icon tile, the lowercase `quad` wordmark. For brand moments only (Welcome, auth headers) |
| `ConfirmDialog(title, message, confirmLabel, onConfirm, onDismiss, destructive, loading, icon)` | Confirmations. `destructive` (delete, discard, remove, suspend, cancel an order): the confirm action is a solid pill in the theme's error colour and Cancel a neutral text button (red text alone is too close to the berry text buttons). For bullets, a field or an `InlineError`, pass `content = { … }` instead of `message` (and `confirmEnabled`); it scrolls when it's too tall |
| `DialogConfirmButton(label, onClick, destructive, enabled, loading)`, `DialogDismissButton` | The same two buttons for a hand-built `AlertDialog` (`ModerationActionDialog`, the suspended-account dialog) |
| `ServerSettingsDialog` | Debug server override (Welcome → "Dev server · …", and Settings) |
| `SheetKeyboardBackdropEffect()` | Call in a bottom sheet that has text fields (see Platform notes, iOS) |

Also: `Format.price / rating / parsePriceCents`, `rememberTimeFormat()` (`time`, `dateTime`, `dayLabel`, `inboxStamp`, `age`, `ago`, `date`, `memberSince`, `countdown`), enum labels (`ListingStatus.label`, `OrderStatus.label`, `ReportStatus.label`, `ModerationAction.label`, …; `ListingCategory`, `ListingCondition`, `ReportReason` already have `.label` in core), and `Validate.*` for `Limits`-based checks.

Storefront pieces that stay in `feature/listing/`: `PriceInput` (dollar field: decimal keypad, `$` prefix, thousands
separators while typing, at most two decimals), `PhotoViewer` / `PageDots` (full-screen photos on black, edge to edge:
swipe, pinch / double-tap zoom, pan) and `ListingCondition.hint` (condition explanations).


## Theme and conventions

The product is **Quad** and its look is **Golden Hour**: the quad at sunset, when everyone is outside. Social and
upbeat, a consumer app rather than a classifieds board. (`CampusMarketTheme` / `CampusTheme` keep their old names: they
are code identifiers, like the `com.campusmarket` packages.)

- **The mark: "Sun on the Quad".** The campus quad from above: four lawn panels split by crossing paths (8-unit gaps on
  a 96-unit grid, drawn within 18..78) with the sun in the top-right cell. Three 26×26 panels each have their outer
  corner rounded (r = 12); the fourth cell holds a circle (centre 65,31, r = 13). Every shape is one solid colour: white on
  the gradient, berry `#D6305C` on light surfaces, `#FFEEF0` on dark. `QuadMark` draws it with a Compose `Path`; the
  same numbers live in `scripts/generate_app_icon.py` (launcher and iOS icons) and the server-rendered pages. Change
  all three together.

- **One gradient, only for brand moments.** Amber `#FFC35A` → dusk pink `#FF6B81` → deep berry `#B02A78`:
  `CampusTheme.brandGradient` (135°, app icon), `CampusTheme.heroGradient` (160°, Welcome hero; amber at the top, so
  status-bar icons over it are dark) and `CampusTheme.actionGradient` (120°, coral `#F0603A` → berry `#D6305C` → deep
  berry, white content) for `PrimaryButton`, selected `FilterChip`s and the selected bottom-bar tab. Don't use the
  gradients anywhere else; everything else stays quiet so listing photos carry the screen.
- **Colour roles.** Light: warm white `#FFF8F3` background, white cards and raised surfaces (all `surfaceContainer*`
  except `Highest` are white), ink `#2A1320` text, muted `#7A5A66`, berry `#D6305C` primary. Dark ("after dark"):
  `#1D0F18` background, plum `#2E1826` cards, `#FFEEF0` text, muted `#C49AA8`, primary `#FF9166` with after-dark text on
  it. `secondaryContainer` is the solid "selected" fill (berry, white text) that Material components draw for selected
  chips. `CampusTheme.colors` adds success / warning / neutral containers for status chips, `star` (amber) and `price`
  (berry on light, amber `#FFB347` on dark). Raw swatches are in `GoldenHour` for brand drawing only. Never use literal
  colours in features.
- **Contrast.** Every text pairing is WCAG AA (≥ 4.5:1; checked for all on-colour roles, status chips and avatar
  colours). Berry text is 4.50:1 on the warm-white background and 4.73:1 on white, which is why light dialogs, sheets,
  menus and bars are white: don't put primary-coloured text on `surfaceContainerHighest` (the one tinted light
  container: incoming chat bubbles, image placeholders). White labels on the action gradient measure ≥ 4.6:1 because
  berry arrives at 30% and labels are centred; keep gradient labels short and centred. On the hero, white type sits on
  the berry half only (the tagline measures ≥ 4.9:1).
- **Type.** Plus Jakarta Sans, bundled in `composeResources/font/` as static 500/600/700/800 instances of the upstream
  variable font (`google/fonts`, `ofl/plusjakartasans`), so every target including Android API 24/25 gets real
  weights. The files are subsets made by `scripts/subset_fonts.py` (fontTools): every Latin, Latin Extended,
  punctuation, currency, arrow and symbol codepoint of the originals and the features text gets by default (kerning,
  ligatures, marks, plus `tnum` / `pnum` / `case`), without the stylistic alternates, glyph names and three stray Greek
  letters; 35% smaller, pixel-identical on screen. Other scripts fall back to the system font as before. The licence
  has no reserved font name, so the subsets keep the family name. To add a weight or a glyph range, run the script on
  the upstream instance. Licence: SIL OFL 1.1, shipped as `composeResources/files/licenses/PlusJakartaSans-OFL.txt` (keep it with the
  fonts). 800 with tight tracking (−0.04 em on display sizes) for display, headlines, `titleLarge`, prices and the
  wordmark; 700 for `titleMedium/Small`; 500 body; 600 labels. Use `MaterialTheme.typography.*`.
- **Shape.** Cards and listing tiles 18 dp (`shapes.medium`), buttons and chips are pills (`CampusTheme.pill`), dialogs
  and bottom sheets 28 dp (`shapes.extraLarge`), thumbnails and banners 14 dp (`shapes.small`), fields 10 dp.
- **Voice.** Warm and short. The wordmark is lowercase `quad`; UI copy is normal sentence case and says "Quad" when it
  names the product.
- Spacing: `Spacing.xs/sm/md/lg/xl/xxl` and `Spacing.screen` (16 dp horizontal screen padding). Shapes come from the theme (`MaterialTheme.shapes.medium` for cards).
- Status bar: a screen that draws its own colour under the status bar calls `StatusBarIcons(darkIcons = …)` (in
  `platform/`); leaving the screen restores the system default. The Welcome hero (dark icons) and the photo viewer
  (light icons) do this. A dialog that should cover the whole screen, status bar and home indicator included, uses
  `edgeToEdgeDialogProperties()` (Android `decorFitsSystemWindows = false`; iOS `usePlatformInsets = false`, since a
  plain `Dialog` stops at the safe area) and pads its controls with the bar insets.
- **Motion.** Screen changes are Material's shared-axis X, kept short (300 ms): a pushed screen slides in a little and
  fades in, back reverses it; switching tabs is a quick cross-fade. Both NavHosts in `AppNavigation` set this; don't add
  per-route transitions. Haptics go through Compose's `LocalHapticFeedback` (no-op on desktop): tab switches, filter
  chips, the favourite heart, rating stars and confirm buttons in `ConfirmDialog` already do it.
- **Loading, empty, error.** First loads show skeletons in the shape of the content (the paged lists do this for you);
  a bare spinner is for things without a known shape. Empty states say what goes here and offer the action that fills
  it. Errors come with a retry. Touch targets are at least 48 dp.
- Screenshots: opt-in galleries in `shared/src/jvmTest` render phone-sized (412 × 892 dp at 2x) PNGs in light and dark,
  `./gradlew :shared:jvmTest --tests '*<Gallery>*'` with `CAMPUS_MARKET_SCREENSHOTS=/some/dir` (they skip without it).
  Live ones also need `CAMPUS_MARKET_LIVE=http://127.0.0.1:8080` (a dev server with the demo data; they only sign in and
  navigate). Use `127.0.0.1`: the test JVM may not reach `localhost` over IPv6. `CAMPUS_MARKET_FONT_SCALE=1.3` renders
  larger text, `CAMPUS_MARKET_SHOT_PHOTOS=<dir of jpgs>` gives the fake-server galleries photos.

  | Gallery | Server | Covers |
  |---|---|---|
  | `BrandScreenshotTest` | live | welcome, sign in, browse, filters, empty search, listing detail, favorites, sell, inbox, conversation, profile, settings, an order, the report queue, and the seller's own listing / edit / my listings / sold listing |
  | `UiCoreScreenshotTest` | fake | auth screens and errors, suspension notice, the shell's loading / offline states, tab badge, push transition, landscape, 1.3× text, and a gallery of the shared components, states and dialog |
  | `ProfileModerationScreenshotTest` | live | every profile and moderation screen as a student, a moderator and an admin |
  | `ProfileModerationStatesScreenshotTest` | fake | profile / moderation states the demo data lacks (many ratings, photo avatars, loading, errors, a busy log) |
  | `ChatOrdersGalleryTest` | fake | every chat, order (each status, buyer and seller), checkout and payouts state, light, dark and 1.3x (`CAMPUS_MARKET_SHOT_ONLY=chat,orders,…` narrows it) |
  | `ListingScreenshotTest` | fake + local photo server | every browse / listing state: loading, empty, error, sold / reserved / removed / blocked, long titles, 1.3x, landscape |

  Tests that change data on a live server opt in with `CAMPUS_MARKET_LIVE_API` instead: `LiveServerTest` (storefront
  view models and the Sell form, against the demo data, cleaning up after itself) and `LiveServerE2ETest` (chat, orders,
  ratings and payouts between two throwaway accounts).
- Copy: plain English strings inline. No string resources yet (single locale). Sentence case. Money always via `Format.price` / `PriceText`.
- **Performance.** Coil (`newImageLoader` in `ui/components/Images.kt`, installed by `App()`) keeps decoded images in a memory cache sized per platform and downloads in a disk cache under the platform cache directory (`platform/ImageCache.*`); requests are sized to the composable. Lazy lists and grids need a stable `key` (and a `contentType` when rows differ). The `:core` wire types are declared stable for the Compose compiler (`shared/compose-stability.conf`), so a row given an equal copy after a refresh skips recomposition; `./gradlew :shared:compileKotlinJvm -PcomposeReports` writes the compiler's stability reports to `shared/build/compose-reports`. Measure on the `benchmark` build, not debug.
- Text inputs: enforce `Limits` with `maxLength` and `Validate.*`. The server stays authoritative, so always surface `ApiError.fields`.
- Never call `HttpClient` directly from features, never store tokens, never load server URLs without `RemoteImage` / `ExternalLinks`.
- Keep `iosMain` minimal. Anything platform-specific goes behind a small interface in `platform/` (ask the foundation owner).
- Tests: `shared/src/commonTest` runs on the JVM (`./gradlew :shared:jvmTest`). `data/TestSupport.kt` is the test kit: `Harness` (the real `ApiClient` on a scripted `MockEngine`, with user messages and recorded external links; pass `scope = this` to run HTTP on the test scheduler; without a scope it answers at once on the calling thread, so nothing races; `threaded = true` only for multipart uploads), `respondJson` / `respondError`, request `bodyText` / `decode`, `withMainDispatcher` and `awaitValue`. UI tests in `shared/src/jvmTest` drive the real `App()` with `TestApp.kt`: `FakeServer { req -> … }` (records requests), `startTestApp(server, signedIn(user))`, and `awaitText` / `click` / `clickDescription` / `type` / `back`, which wait for their node and click on the UI thread (navigation must run there). `AppSmokeTest` covers the shell; copy a feature's UI test for your flows. Screenshot galleries extend `ScreenshotGallery`.

## Platform notes

- **Android:** `CampusMarketApp` calls `initCampusMarket(context)`. `MainActivity` is `singleTask` and forwards `campusmarket://` intents (cold start + `onNewIntent`) to `DeepLinks`. Cleartext HTTP is allowed only in the debug and benchmark builds (`src/debug/res/xml/network_security_config.xml`, `src/benchmark/…`). Images come from the system photo picker, so the app needs no media permissions. The window theme (`Theme.Quad`, `res/values*/themes.xml`) paints the warm-white / after-dark background behind Compose and the Android 12+ splash, so launch never flashes white in dark mode (and sets `windowLightStatusBar` per theme for the window before Compose takes over). On Android 12+ the status bar takes the app window's icon colour only when the system splash window goes, and then fades to it, so `MainActivity` takes over the splash view (`setOnExitAnimationListener`: a short hold, then a fade) and, when nobody is signed in, starts with the Welcome hero's dark icons (`startsOnBrandHero()`): the change happens under the splash instead of on the first screen. `StatusBarIcons` restores the theme's icon colour when its screen leaves. (On Android 13 the splash itself shows the launcher's icon colour until then; that part is the system's.) Predictive back is on (`android:enableOnBackInvokedCallback`), so handle back with `BackHandler` (never `onBackPressed`); in-app back animations need Android 14+.
  Release and benchmark builds run R8 (keep rules beyond the libraries' own: `androidApp/proguard-rules.pro`): after adding a library that reflects (or a serializable type looked up by name), run `installBenchmark` and walk the flow it touches. Walked on the minified build: photo picker → compression → upload → post a listing, buy through the fake checkout and return by deep link, confirm receipt, rate; no extra keep rules were needed.
- **iOS:** `MainViewController()` starts Koin. `iOSApp.swift` forwards `.onOpenURL` to `MainViewControllerKt.handleDeepLink(url:)`. `Info.plist` registers the `campusmarket` scheme, has photo/camera/local-network usage strings, sets `NSAllowsLocalNetworking` (HTTP to localhost/.local only), requires `arm64`, and sets `UIViewControllerBasedStatusBarAppearance` to NO so `StatusBarIcons` can set the app-wide status bar style (the SwiftUI host never asks the Compose view controller). `PrivacyInfo.xcprivacy` declares no tracking and the required-reason APIs in the binary (user defaults, file timestamps). The display name comes from `APP_NAME` in `Configuration/Config.xcconfig`. The launch screen is the `LaunchBackground` colour from the asset catalog (warm white / after dark).
  - Built and run on the iPhone 17 simulator (iOS 26.5, Xcode 26.6); every flow was walked through there, light and dark. Not yet run on a device.
    Last full pass: register / verify / sign in, browse and scroll, listing and photo viewer, posting with a photo from the picker, chat (send, receive, unsent message with Retry / Delete, draft kept), checkout in Safari and back, confirm, rate, report a problem, the report sheet and moderation as moderator and admin; forms with the keyboard up in portrait and landscape, the tab rail on both landscape sides.
  - Cutting the app off from the server on the simulator: its network is the Mac's, so run a small TCP proxy to the dev server on another port, point the app at it (Settings → API server), and stop / start the proxy. That exercises the chat socket's reconnect and the outbox; an `NWPathMonitor` change needs a real device (airplane mode).
  - A sheet (and its scrim) stops at the top of the keyboard, and the iOS keyboard is translucent, so the screen behind would show through the keys. A sheet with text fields calls `SheetKeyboardBackdropEffect()` and the root (`App`) paints the sheet colour behind the keyboard (`ui/components/SheetBackdrop.kt`).
  - A burst of deletes from XCUITest (`ios-drive.sh del 72`) removes a few characters fewer than asked in every Compose field (a native field takes them all); typing at the same speed loses nothing. Clear a field in a test by deleting until it is empty.
  - iOS-only glue in `MainViewController.kt`: a tap outside a text field clears focus and hides the keyboard (number and decimal pads have no return key), and `FollowDynamicType` (`platform/DynamicType.kt`) applies a text-size change while the app runs (Compose reads it only at launch; same category → scale steps as Compose). In `AppNavigation`, the iOS edge swipe does nothing on the Sell / Inbox / Profile roots instead of popping to Browse; on pushed screens it is Back (and goes through `BackHandler`s, e.g. the unsaved-listing prompts).
  - Fast typing (`scripts/ios-drive.sh type …`, i.e. XCUITest `typeText`, or a hardware keyboard) used to drop and reorder characters in the report sheet. The cause was ours, not the sheet: see "Text fields in sheets and dialogs" under State patterns. Checked by typing a 72-character sentence several times and reading the value back from the accessibility tree, in the report sheet (listing and user), the moderation note dialog, the delete-account dialog, search, the chat composer and the Sell title / description.
  - VoiceOver: Compose builds a merged node's label from the texts that are on screen (about the first five, none of the clipped ones), in layout order, and adds them to a `contentDescription` rather than replacing them. So a half-visible tile or row had no label or half of one, and a row with an avatar read the name twice. A row or tile whose meaning is in several texts sets one summary: `Modifier.clickable(…).clearAndSetSemantics { contentDescription = … }` (clickable first, so it stays a button), starting with the text people know it by. Listing tiles, inbox rows, chat bubbles, the report / dispute / log / staff rows, `MenuRow` (`trailingDescription` for what its trailing content says) and `ListingLine` do this. When the row holds another control (the tile's heart, a staff row's role button), set the description on the row and clear the texts' own container instead, so the control stays reachable. Pass `contentDescription = null` to an `Avatar` beside the printed name, and merge label / value pairs (`semantics(mergeDescendants = true) { }`). UI tests find such rows by content description (`hasContentDescriptionStartingWith`, `shows` and `clickDescription(…, substring = true)` in jvmTest); `hasText` no longer sees their texts. Inspect with `scripts/ios-drive.sh tree`.
- **Desktop:** dev harness only. URL opening uses `java.awt.Desktop`. There's no URL-scheme registration, so pass a deep link as a program argument instead.

## Token storage

`TokenStore` is implemented by `SecureTokenStore` over a platform `SecretStorage` (`data/TokenStore.kt`), which each platform module provides:

| Platform | `SecretStorage` | Notes |
|---|---|---|
| Android | `KeystoreSecretStorage` | AES-256-GCM, non-exportable Android Keystore key; `iv || ciphertext` in the private prefs file `campusmarket_secure_session`, excluded from backup and device transfer (`androidApp` `backup_rules.xml` / `data_extraction_rules.xml`). |
| iOS | `KeychainSecretStorage` | `kSecClassGenericPassword`, `AfterFirstUnlockThisDeviceOnly`, not iCloud-synced. Verified on the simulator: the session survives a relaunch (`QuadFlowTests.testSessionSurvivesRelaunchUntilSignOut`). Needs a signed build (see Running). |
| Desktop | `SettingsSecretStorage` | **NOT SECURE**: plain java.util.prefs, dev harness only. |

- Unreadable / undecryptable / corrupt stored data (e.g. invalidated Keystore key) clears the stored session and the app starts signed out; no crash. A failed write keeps the session in memory only.
- One-time migration: a session still in the old plain `Settings` key (`session.v1`) is moved into the secure store and the plain copy deleted (on secure platforms only).
- Non-secret preferences (debug server URL) stay in plain `Settings`. Reads are synchronous, so `SessionManager` restores the session at launch unchanged.

## Pre-release TODOs (not blocking feature work)

- ~~Token storage hardening~~ (done, see "Token storage" below; the iOS Keychain path is verified on the simulator, not yet on a device).
- Release base URL / HTTPS server config (currently debug defaults + override).
- String resources and localisation if needed.
