# Developer guide

This doc covers building Origin Ghost Player from source, the package-name spoof it relies on, how
the identity bridge actually works, and how to cut a signed release. For what the app does and how
to install it, see the [README](../README.md).

## ⚠️ Read this first: the package name is load-bearing

`applicationId = "com.kugou.android.lite"` in [`app/build.gradle.kts`](../app/build.gradle.kts) is
**deliberate, not a mistake**. OriginOS's native "OriginPlayer" media widget only activates for a
short whitelist of packages it recognizes as media players, and this app's `applicationId` spoofs
Kugou Music Lite's (酷狗音乐概念版) real package id to land on that whitelist. **If you change it,
OriginPlayer will stop reacting to this app.**

Two consequences:

1. **It cannot be installed alongside the real Kugou Music Lite app** (same applicationId →
   package conflict). Uninstall the real app first if you have it.
2. **This can never ship on the Play Store** or be distributed as if it were Kugou Music Lite. It's
   a personal sideload — install it on your own device, understand what you're installing.

The Kotlin package / `R` namespace is a separate, harmless identifier:
`com.originghostplayer.android`. It's just where the code lives; it isn't checked by OriginOS.

## How it works

Unlike [origin-isle](https://github.com/fvhde/origin-isle), which posts its own notification-based
"island" cards via vivo's reverse-engineered SuperX protocol, this app doesn't post any notification
of its own at all. Instead:

1. `MediaProbeListener` (a `NotificationListenerService`) watches the system for other apps' active
   `android.media.session.MediaSession`s — via the session token carried on `Notification.MediaStyle`
   notifications, falling back to `MediaSessionManager.getActiveSessions()` for apps that omit it
   (e.g. Firefox).
2. It mirrors whatever it finds — metadata, playback state — onto its **own** `MediaSession`,
   created under the spoofed `com.kugou.android.lite` identity. Real playback control taps are
   forwarded straight back to the real source app's `MediaController.transportControls`.
3. That's it. No notification, no UI most of the time. OriginOS's OriginPlayer sees a whitelisted
   package with an active, fully-formed media session (real players' sessions all carry a
   `mediaButtonReceiver` and a `launchIntent` — this app's session does too, closing a gap that
   initially kept OriginPlayer from reacting) and renders its native widget for it — regardless of
   which real app is actually playing. Confirmed working with Metrolist and Firefox as sources.

One caveat found during testing: OriginPlayer's native tap handler doesn't honor
`MediaSession.setSessionActivity()` — it just opens the app that owns the session identity (this
app). `MainActivity` works around that by redirecting to the real source app's launch intent
whenever it's opened while something is tracked, rather than showing its own UI.

## Build & run

Requires Android Studio (AGP 8.9+) and the `android-36` SDK platform.

1. **Open the folder in Android Studio** and let it sync.
2. **Run** onto a vivo/iQOO OriginOS device. (It will build and install fine on any Android 14+
   device, but OriginPlayer itself only reacts on OriginOS.)
3. On first launch, grant notification access when prompted — that's the only permission needed.
4. Play something in any media app and check OriginOS's status bar / lock screen / always-on-display
   for the native OriginPlayer widget.

## Signed release build

The debug build works fine for personal use. To build a release APK you can update over time, this
project is signed with the **same key as origin-isle** — same maintainer, same trust anchor.

Create `keystore.properties` at the repo root (gitignored — never commit it):

```
storeFile=../originisle-release.jks
storePassword=...
keyAlias=originisle
keyPassword=...
```

A `keystore.properties.template` is committed as a reference — copy it to `keystore.properties` and
fill in the real values on the machine that holds the key. `keystore.properties`, `*.jks` and
`*.keystore` are gitignored — never commit them.

Then run the release helper from the repo root:

```
./scripts/release.sh
```

It builds `:app:assembleRelease`, verifies the signature, and writes the signed APK plus its SHA-256
checksum to `dist/`, printing the signing certificate's fingerprint — it should match origin-isle's.
Upload **both** the APK and the `.sha256` file to the GitHub Release.

> Switching between a debug-signed and release-signed install requires uninstalling first (Android
> rejects a signature mismatch), which also drops notification access — re-grant it after.

## Verify a release is genuine

This app reads your notifications (to find media sessions), so only ever install a build you can
prove came from the maintainer. To check a downloaded APK:

```
# 1. checksum matches the one published on the release
shasum -a 256 origin-ghost-player-<version>.apk

# 2. it was signed by the maintainer's key — compare against the fingerprint below
apksigner verify --print-certs origin-ghost-player-<version>.apk
```

Official signing certificate SHA-256 (same as origin-isle):

```
5D:2D:FA:7E:F6:A9:6B:95:4A:C2:43:61:A3:30:BA:9B:2C:EA:45:18:C5:B8:63:58:C9:F4:FF:B1:1D:79:E6:00
```

A build whose checksum or certificate fingerprint doesn't match is **not** an official build — do
not install it.
