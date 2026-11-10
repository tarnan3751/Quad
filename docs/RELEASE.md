# Release checklist (app stores and production)

Later-scope checklist. Nothing here is done yet. Review store policy pages again at release time; they change often.

## 1. Accounts and identifiers
- [ ] Apple Developer Program membership (organization or individual) and App Store Connect access.
- [ ] Google Play Console developer account (identity verification; new personal accounts have extra testing requirements before production access).
- [ ] **Decide the final bundle id and URL scheme before the first store submission: they can't change afterwards.** The product is now called Quad, but the Android `applicationId` / iOS bundle id are still `com.campusmarket.app` and the deep-link scheme is still `campusmarket://` (left alone in the rename on purpose). Once an app is published, its application id / bundle id is permanent (a new one is a new app, losing reviews and installs), and a scheme that shipped has to keep working for old installs and for return URLs already handed to Stripe. If either should say "quad", change it now in one go: `applicationId` in `androidApp/build.gradle.kts`, `BUNDLE_ID` in `iosApp/Configuration/Config.xcconfig`, the scheme in `AndroidManifest.xml`, `Info.plist` (`CFBundleURLTypes`), `DeepLinks.kt` and the server's `APP_DEEP_LINK_BASE` default. Kotlin packages, module names, database and env-var names are internal and can stay.
- [ ] Register the `campusmarket://` deep-link scheme in both apps (it is used for checkout and onboarding returns), and consider verified https links (Android App Links, iOS Universal Links) for production.
- [ ] Store listing name "Quad": check availability in both stores (and trademark clearance) before announcing; the display name comes from `app_name` (Android) and `APP_NAME` (iOS `Config.xcconfig`).
- [ ] Support email, privacy policy URL, terms of service URL, marketing/support URL.

## 2. Signing and builds
- [ ] Android: create an upload key, enrol in Play App Signing, build a release AAB (`bundleRelease`) with minify/shrink verified, keep the keystore backed up outside the repo.
- [ ] iOS: App ID, distribution certificate, provisioning profile (or automatic signing), archive in Xcode, upload via Xcode/Transporter. Set `TEAM_ID` in `iosApp/Configuration/Config.xcconfig`. The app builds and runs on the simulator; it hasn't been archived or run on a device yet.
- [ ] Set production server URL in the clients; release builds must use https only (no cleartext/ATS exceptions).
- [ ] CI job for release builds with secrets in the CI secret store.
- App version: bump `APP_VERSION_NAME` / `APP_VERSION_CODE` in `gradle.properties` (the single source of truth: Android `versionName`/`versionCode`, generated `BuildInfo` for the Settings "About" row), then run `./gradlew syncIosVersion` to update `iosApp/Configuration/Config.xcconfig` (a test fails if they drift).
- App icon: edit the geometry in `scripts/generate_app_icon.py` (needs Pillow) and re-run it; it rewrites the Android vector/adaptive/legacy icons and the iOS `AppIcon.appiconset`.
- [ ] Test on real devices and the lowest supported OS versions (Android minSdk 24).

## 3. Privacy and data disclosures
- [ ] Apple privacy manifest: `iosApp/iosApp/PrivacyInfo.xcprivacy` declares no tracking and the required-reason APIs found in the binary (user defaults CA92.1, file timestamps C617.1 / 3B52.1). Still to do: add `NSPrivacyCollectedDataTypes` to match the App Privacy "nutrition labels" in App Store Connect, and re-scan after adding SDKs.
- [ ] Google Play Data safety form. Data we handle: email, name, user-generated photos/text/messages, purchase and payout information (through Stripe), user ids, crash/diagnostic data if added.
- [ ] Privacy policy that matches the above, including retention and deletion, and Stripe's role as processor.
- [ ] Photo handling: we only use the photo picker / camera on user action; no location is collected. Confirm permission strings (`NSCameraUsageDescription`, `NSPhotoLibraryUsageDescription` when needed) are accurate.
- [ ] Age rating questionnaire (marketplace, user-generated content, chat). Decide the minimum age; campus emails are not proof of age.
- [ ] Encryption export compliance answers (standard HTTPS only); then add `ITSAppUsesNonExemptEncryption` = NO to `Info.plist` so uploads don't ask each time.

## 4. Marketplace and user-generated-content requirements
Both stores require these for apps with UGC and marketplaces. The server already provides them; verify the apps expose them and that reviewers can find them.
- [ ] **Account deletion**, in-app and discoverable (Apple guideline 5.1.1(v), Google Play account deletion policy, which also needs a web URL for deletion requests): `DELETE /me` (soft delete, anonymises, revokes tokens, takes listings down; refused while orders are `PENDING_PAYMENT`, `PAID` or `DISPUTED`). Suspended users, who can't sign in, delete through `POST /auth/delete-account` (email + password), offered by the app's "account suspended" notice. Provide a web page/form or email path for people without the app (it can call the same endpoint).
- [ ] **Reporting content and users** in-app (listings, users, messages): `POST /reports`.
- [ ] **Blocking users**: `POST /users/{id}/block`; blocked users cannot message or order, and their listings are hidden.
- [ ] **Moderation**: a way to act on reports within a stated time frame (moderator queue exists), ability to remove content and suspend abusers, published contact info for complaints. Staff the **dispute queue** too (Moderation → Disputes): disputed payments stay frozen until a moderator refunds or releases them, including every paid order of a seller who gets suspended. Decide and publish a target response time.
- [ ] Terms (EULA) that prohibit objectionable content and abusive users, accepted at sign-up (Apple 1.2).
- [ ] Prohibited-items policy (weapons, drugs, counterfeit goods, tickets resale rules per campus/state), reflected in report reasons.
- [ ] Provide reviewers with demo credentials (a verified account, a seller with payouts, and a moderator) and a note on how the Stripe test flow works. Seeded accounts only exist in dev: create review accounts in production deliberately.
- [ ] Payments: physical goods sold between users via Stripe Checkout in the browser are not subject to in-app purchase rules; state this in the review notes. Do not sell digital goods.
- [ ] Sign in with Apple is only required when offering third-party social login; we use email + password, so not required. Re-check if that changes.

## 5. Stripe Connect (live mode)
- [ ] Complete the Stripe platform profile and business verification; accept the Connect platform terms; configure branding for Express onboarding and Checkout.
- [ ] Review platform responsibilities (liability for negative balances/refunds/disputes) and the fee model (`PLATFORM_FEE_BPS`).
- [ ] Switch to live keys: `STRIPE_SECRET_KEY=sk_live_...`; create a live webhook endpoint at `https://<host>/api/v1/stripe/webhook` for `checkout.session.completed`, `checkout.session.expired`, `account.updated`, `charge.refunded` (Connect events for `account.updated`) and set `STRIPE_WEBHOOK_SECRET`.
- [ ] Verify the transfer flow end to end with a small real charge, including refund and a payout to a real connected account.
- [ ] Tax and compliance: decide on sales tax handling, 1099-K reporting responsibilities, supported countries (currently `usd`).
- [ ] Dispute handling process (chargebacks) and support contact. In-app disputes (buyer reports a problem; payment frozen; moderator refunds or releases) cover orders until they are completed. Refunds and Stripe disputes/chargebacks after an order is COMPLETED stay manual: the server only logs `charge.refunded` on a completed order; reverse the transfer in the dashboard and document who does it.
- [ ] Confirm the hold policy with counsel: payments are released automatically `AUTO_RELEASE_HOURS` (72) after payment unless the buyer reports a problem; the Terms say 72 hours, so change both together. Stripe holds funds on the platform balance in the meantime (separate charges and transfers): check the platform's payout schedule and balance needs.

## 6. Production configuration
- [ ] `DEV_MODE=false`; a random `JWT_SECRET` of 32+ characters from a secret manager. In this mode the server refuses to start with a weak secret or without `STRIPE_SECRET_KEY`, `STRIPE_WEBHOOK_SECRET` and `SMTP_HOST`.
- [ ] `PUBLIC_BASE_URL` = public https origin; TLS terminated by a reverse proxy/load balancer; WebSocket upgrade and sensible idle timeouts allowed for `/ws`.
- [ ] Real email: `SMTP_HOST` etc. and a verified sender/domain (SPF, DKIM, DMARC) so verification codes arrive. Without it codes are only logged.
- [ ] `ALLOWED_EMAIL_SUFFIXES` and `ADMIN_EMAILS` set deliberately. Do not run the seed tool against production (it refuses when `DEV_MODE=false`).
- [ ] Photo storage: the current local `UPLOADS_DIR` needs a persistent volume and does not scale past one node; move to object storage (S3 or similar) behind `PhotoStorage` and a CDN before real traffic.
- [ ] Single-node limits: the chat hub is in-process and the payments sweep (checkout expiry, suspended-seller holds, 72 h auto-release) runs in-process, so run one instance until those are externalised. The sweep is safe to run twice (claims and idempotency keys), but two instances double the processor calls. Alert on repeated "Couldn't auto-release order" warnings: a payout that keeps failing is retried every minute.
- [ ] Managed PostgreSQL 17 with a dedicated least-privilege user; migrations run on boot, so review each release's migration first.
- [ ] Logging/metrics/alerting, error reporting, uptime check on `/api/v1/health`.
- [x] Client token storage hardening (Keychain / Android Keystore) is implemented; the iOS Keychain path is verified on the simulator (still check on a device).
- [ ] Rate limits confirmed for register/login/verify/resend behind the proxy: set `TRUSTED_PROXY_HOPS` to the number of proxies that append to `X-Forwarded-For` (usually 1), otherwise every client shares the proxy's budget.

## 7. Backups and recovery
- [ ] Automated daily PostgreSQL backups plus point-in-time recovery; retention policy documented.
- [ ] Back up uploaded photos (or rely on object-storage versioning).
- [ ] Restore drill: restore into a scratch database and run the smoke test (`scripts/smoke.sh`) against it.
- [ ] Back up the Android upload keystore and Apple certificates/keys; document who holds them.
- [ ] Runbook: rotating `JWT_SECRET` (invalidates sessions), rotating Stripe keys, rolling back a release.

## 8. Store submission
- [ ] Screenshots for required device sizes, app icon, description, keywords, category (Shopping), support URL.
- [ ] Internal testing (TestFlight / Play internal track) with a few real campus users before public release.
- [ ] Review notes: demo logins, explanation of campus-email verification, payment flow, moderation and deletion features.
- [ ] Staged rollout and a plan for the first week of moderation load.
