# GenZ — Android app (Capacitor wrapper)

This repo turns the existing GenZ web app (deployed at **https://publictalk.in**)
into an installable Android **.apk**, built automatically by GitHub Actions.

## How it works

This is **not** a rewrite. No PHP, no JS from the GenZ web app was touched or
copied in here — `capacitor.config.json`'s `server.url` points the app
straight at `https://publictalk.in`, so the APK is just a WebView shell that
loads your live site. Every feature (chat, groups, calling, wallet,
market/promotions, Z-AI) keeps working exactly as it does on the website,
and the app always reflects whatever is currently deployed there — no app
rebuild needed when you update the website.

The one piece of real native code is `android/app/src/main/java/in/publictalk/genz/MainActivity.java`.
Android's WebView blocks camera/mic access (`getUserMedia`) by default, which
would silently break audio/video calling, so this file:
1. Requests the CAMERA and RECORD_AUDIO runtime permissions on first launch.
2. Auto-grants the WebView's own `getUserMedia` permission prompt so calls can
   actually start.

Nothing about the WebRTC signaling/calling backend (`backend/api/calls/*`)
or its JS is part of this repo at all — it's untouched, on the server, same
as always.

## v7.62 — FCM wired in (Part B), Android half

Step 3 of the 3-part plan (popup bug → TURN relay → FCM). This is the
**native Android half**; the matching backend half ships in
`GenZ-v7.62.zip` (web/backend package) — both must be uploaded/deployed
together, since this half has a server it pushes to and that server has
nothing to push to without this half.

**What FCM is used for here, precisely:** a THIRD path alongside the two
that already exist — app.js's own live polling while the WebView is
foregrounded, and `CallPollService`'s ~3s native poll loop (v7.43,
already fixed in v7.60). It is not a new incoming-call UI and does not
replace either existing path. It's purely a fast "wake up and check for a
call right now" nudge: the server sends a data-only push the instant a
call starts, `FcmService` receives it and pokes `CallPollService` to poll
immediately instead of waiting up to ~3s — `CallPollService`'s own
already-fixed notification/Answer/Decline code is what actually shows the
popup, reused as-is. If FCM fails for any reason on a given device
(Firebase misconfigured, no push arrives, no network at that instant),
calling is completely unaffected — the ~3s native poll from v7.43/v7.60
still runs exactly as before.

**What changed:**

- New file `android/app/google-services.json` — the Firebase Android app
  config the user downloaded from their Firebase console (project `genz`,
  ID `genz-78516`, package `in.publictalk.genz`).
- `android/app/build.gradle` — added the Firebase BOM + `firebase-messaging`
  dependency. (The `google-services` Gradle plugin was already scaffolded
  in this project's `build.gradle` files from the start, conditional on
  `google-services.json` existing — it just had nothing to apply to until
  now.) Bumped `versionCode` 747→748, `versionName` "7.60"→"7.62".
- New file `FcmService.java` — `onNewToken()` registers this device's FCM
  token against the logged-in user via the new backend endpoint
  `fcm/register_token.php`; `onMessageReceived()` pokes `CallPollService`
  as described above. Does not build or show any notification itself.
- `CallPollService.java` — added a small static `pokeNow()` plus an
  `activeInstance` reference so `FcmService` can trigger an immediate poll
  cycle on the already-running service. No change to the actual polling,
  fraud/timing, or notification logic itself.
- `CallListenerPlugin.java` — `start()` (already called by app.js right
  after login) now also fetches this device's current FCM token and sends
  it to the server, covering the case where Firebase issued a token before
  this particular login (Firebase's own recommended practice, since
  `onNewToken()` alone only fires again on a token *rotation*).
- `AndroidManifest.xml` — registered `FcmService` for
  `com.google.firebase.MESSAGING_EVENT`.
- Verified: all 5 touched/added Java files brace-balanced and manually
  reviewed; `backend/api/calls/*` is untouched by any of this (this whole
  step lives in `backend/api/push/`, `backend/api/fcm/`, and Android Java
  only).
- **Known limitation, stated plainly**: this couldn't be built or run on a
  real device from here — no Android SDK/emulator in this environment,
  same as every previous Android round. It needs the usual GitHub Actions
  rebuild, then a real test: log in on a device, force-close/lock it, and
  have someone call — the popup should appear about as fast as before
  (this only helps the worst-case slow/Doze scenario, so on a normal
  network the difference may not even be very noticeable — that's
  expected).

## v7.45 — notification still not answering/clearing correctly (live report)

Live testing turned up two more gaps in the same incoming-call notification
flow v7.44 worked on:

1. **Tapping the notification didn't answer the call** — it correctly
   brought the app to the foreground (to the same ringing screen the
   website's own call poller shows, which DID answer fine when tapped
   manually), but the answer itself wasn't going through. Root cause:
   `MainActivity.tryDeliverPendingCallAction()` waits (previously ~6s) for
   app.js to finish booting before it hands the "answer" instruction to the
   page, and the matching wait on the JS side (`handleShareLinks()` in
   app.js) was only ~4.5s. Tapping the notification can relaunch the whole
   app from a cold start — not just resume a paused one — if Android (MIUI
   especially) had killed the backgrounded process, and a full reload of
   the site over a real mobile connection can take longer than either
   budget allowed. Both are widened to ~15s each (~30s combined, still well
   under the 45s a call keeps ringing for) — costs nothing in the normal
   warm case, gives the slow cold-start case room to actually finish.
2. **Notification staying up after the call ended** — v7.44 added a
   `NotificationManager.cancel()` when the poll sees the call is no longer
   incoming, but it stayed guarded behind `lastNotifiedCallId != null`,
   which only remembers state within one service instance. If the
   foreground service gets killed and restarted (it's `START_STICKY`) any
   time between posting the notification and the call ending, the fresh
   instance's memory resets and that first poll skips the cancel — leaving
   an `ongoing` (non-swipeable) notification stuck for good. Fixed by
   always calling `cancel()` when there's no longer an incoming call
   (harmless no-op if nothing's posted), and separately, `MainActivity` now
   also cancels the notification immediately the instant the user acts on
   it (answer tap or the Answer button), instead of relying only on the
   next 3-second poll tick.

None of this touches `backend/api/calls/` — same as every fix in this repo.

## v7.44 — CallPollService notification fixes (built successfully in v7.43.1)

Two real bugs found from live testing of v7.43.1's native call listener,
both in `CallPollService.java`:

1. **Notification not waking the locked screen** — it was posting fine
   (visible once you unlocked and pulled down the shade), just never
   triggering the actual full-screen wake-over-lock-screen behavior.
   Two gaps fixed: no explicit `setVisibility(VISIBILITY_PUBLIC)` was
   set, so a locked-screen privacy setting could suppress it even though
   the notification existed; and priority was capped at `PRIORITY_HIGH`
   rather than `PRIORITY_MAX`, which matters on some devices/OS versions
   for full-screen-intent behavior specifically. Also added
   `setOngoing(true)` so it can't be swiped away by accident while a call
   is actually ringing.
   **If it still doesn't wake the lock screen after this update on a
   Xiaomi/Redmi/MIUI phone** (common with these screenshots' UI style):
   MIUI has its own extra layer of background/pop-up restrictions on top
   of stock Android's. Open MIUI's own Security app → Permissions →
   Autostart → enable for GenZ; Battery → App battery saver → No
   restrictions for GenZ; and Additional permissions → Display pop-up
   windows while running in background → Allow for GenZ. None of this
   can be granted from code — it's a manual one-time step on that OEM.
2. **Notification staying up after the call was answered** — the
   service only ever stopped RE-notifying once a call stopped ringing,
   it never actually cancelled the notification already on screen.
   Fixed: `NotificationManager.cancel()` now runs the moment the call is
   no longer ringing (answered anywhere, declined, or timed out).

## v7.43.1 — fixes a build error from v7.43

The first v7.43 push failed to compile with:
`onResume() in MainActivity cannot override onResume() in BridgeActivity — attempting to assign weaker access privileges; was public`

Cause: `MainActivity.onResume()` (added below, for the native call-listener
work) was declared `protected`, but Capacitor's `BridgeActivity` already
declares `onResume()` as `public` — Java doesn't allow an override to
narrow a method's visibility. Fixed by making it `public` too, matching
the class it overrides. Nothing else changed from v7.43.

## v7.43 — calls ringing while the screen is locked

**The problem:** this app is a plain WebView wrapper, not Chrome. Android
freezes a WebView's JavaScript — every timer, every network request —
once the screen locks or the app sits in the background a while, same as
it freezes a background browser tab. That means the website's own call
polling (`startGlobalCallListener()` in app.js) simply stops running the
instant the phone locks, no matter how fast it's tuned — this is an
Android platform behavior, not a bug in that polling logic.

**The real fix** is Firebase Cloud Messaging (native push) — that needs a
free Firebase project and two credential files shared into the build.
Not set up yet on this project.

**What v7.43 does instead, with zero external setup required:** three new
files — `CallPollService.java`, `CallActionReceiver.java`,
`CallListenerPlugin.java` — add a genuinely separate native Android
background service that polls `backend/api/calls/poll.php` itself,
completely outside the WebView, so Android's freezing rules don't apply
to it. The moment it sees an incoming call it posts a real Android
notification with a full-screen intent (the same mechanism real calling
apps use to wake a locked screen) with Answer/Decline buttons right on
it — same idea as the web/PWA side's push notification actions (see the
main GenZ repo's v7.42 changelog). `app.js` starts/stops this service via
`CallListenerPlugin` right after login/logout (see `syncNativeCallListener()`
in app.js) — nothing about this touches `backend/api/calls/*` on the
server, it only calls the same public endpoints the website already uses.

**Honest trade-off:** this keeps a low-priority "GenZ — Listening for
calls" notification visible at all times while logged in (Android
requires this for any background service to be allowed to keep running
at all) and polls every ~3 seconds, so it uses somewhat more battery than
real push would. That's the real cost of not having FCM wired up yet — if
battery drain becomes a real complaint, Firebase is the next step up from
here.

**One manual step some users may need:** on Android 14+, a fresh install
may need "Full screen notifications" turned on by hand for GenZ once:
Settings → Apps → GenZ → Notifications → Full screen notifications → On.
Without FCM there's no way to prompt for this automatically the way a
Play-Store-reviewed calling app can.

## Getting the APK

Every push to `main` (and manual runs from the **Actions** tab) builds a
debug APK automatically. To download it:

1. Go to **Actions** → the latest **Build GenZ APK** run.
2. Open it → scroll to **Artifacts** → download **genz-debug-apk**.
3. Unzip it — you'll get `app-debug.apk`. Install it on an Android device
   (enable "install unknown apps" for whatever app you copy it over with).

A debug APK is signed with a throwaway debug key — that's fine for testing
and side-loading, but Google Play requires a real release signature (see
below).

## Setting up a signed release build (optional, for Play Store)

The release job is off by default and does nothing until you turn it on:

1. Generate a keystore (`keytool -genkeypair -v -keystore release.keystore -alias genz -keyalg RSA -keysize 2048 -validity 10000`).
2. In the repo's **Settings → Secrets and variables → Actions**, add secrets:
   - `ANDROID_KEYSTORE_BASE64` — the keystore file, base64-encoded (`base64 -w0 release.keystore`)
   - `ANDROID_KEYSTORE_PASSWORD`
   - `ANDROID_KEY_ALIAS`
   - `ANDROID_KEY_PASSWORD`
3. In **Settings → Secrets and variables → Actions → Variables**, add a
   repository variable `ENABLE_RELEASE_BUILD` = `true`.
4. Push again — the **build-release-apk** job will now run and upload a
   signed `genz-release-apk` artifact.

## Local development (optional)

You don't need any of this to get an APK — GitHub Actions does the whole
build. This is only if you want to build locally:

```bash
npm install
npx cap sync android
cd android
./gradlew assembleDebug
# APK lands at android/app/build/outputs/apk/debug/app-debug.apk
```

Requires a local Android SDK + JDK 21 installed (Android Studio provides
both). This was scaffolded and structurally checked in a sandboxed
environment that couldn't reach Google's Maven repo to fully compile it end
to end — GitHub Actions' runners have normal internet access and download
everything (Gradle, the Android Gradle Plugin, the Android SDK) automatically,
so the real first build happens there, not here. If something in the build
does need fixing, the Actions log for the failing step will show exactly
what and where.

## Changing the app's identity

- App name: `capacitor.config.json` → `appName`, and `android/app/src/main/res/values/strings.xml`.
- Package/application ID: `capacitor.config.json` → `appId`, and
  `android/app/build.gradle` → `applicationId` (changing this after a Play
  Store upload is not supported by Google, so pick it carefully up front).
- App icon: replace the images under `android/app/src/main/res/mipmap-*/`
  (or run `npx capacitor-assets generate` with a 1024×1024 source icon).
- Version shown in the Play Store: `android/app/build.gradle` →
  `versionCode` (integer, must increase every release) and `versionName`
  (currently kept in sync with the GenZ web app's own version, `7.43`).
