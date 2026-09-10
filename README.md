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
