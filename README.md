# Origin Ghost Player

**Make OriginOS's native OriginPlayer widget show up for any app.**

OriginOS (vivo/iQOO's Android skin) has a native "OriginPlayer" media widget that only activates for
a short whitelist of media apps it recognizes. Origin Ghost Player bridges any real app's media
session — Spotify, a browser tab, whatever's actually playing — onto a whitelisted identity, so
OriginPlayer renders its native widget for it. The app itself stays invisible: no notification, no
persistent UI, just a background bridge.

This is a hobby project, not an official vivo/iQOO/OriginOS product, and not affiliated with Kugou.

## What it does

- **Watches for any active media session** system-wide (via notification-listener access) and
  mirrors whichever one is actually playing.
- **No card of its own, no notification.** Unlike [origin-isle](https://github.com/fvhde/origin-isle)
  (which posts real notification-based cards), this app publishes nothing visible — OriginOS's own
  native OriginPlayer chrome is the only thing you see.
- **Real playback controls.** Tapping OriginPlayer's controls or its widget forwards straight back
  to the real source app.
- **Survives being swiped away**, same keep-alive recipe as origin-isle.

## Before you install

- **This only works on vivo/iQOO phones running OriginOS.** Elsewhere it just runs invisibly and does
  nothing.
- **It can't be installed alongside the real Kugou Music Lite app** (酷狗音乐概念版,
  `com.kugou.android.lite`) — same applicationId, same as origin-isle's AMap conflict. See
  [docs/DEV.md](docs/DEV.md) for why.
- **It reads your notifications** (to find media sessions), so ONLY install it from this repo's
  Releases page.

## Installing

1. Go to this repo's **[Releases](https://github.com/fvhde/origin-ghost-player/releases)** page and
   download the latest `origin-ghost-player-<version>.apk` **and** its matching `.sha256` file.
2. **Check the checksum** (confirms the file wasn't corrupted or swapped for something else):
   ```
   shasum -a 256 origin-ghost-player-<version>.apk
   ```
   Compare the result to the `.sha256` file you downloaded — they should match exactly.
3. **On your phone**, allow installing apps from this source when prompted, then open the downloaded
   APK to install it.
4. **Open Origin Ghost Player** and grant notification access when prompted — that's the only
   permission it needs.
5. Play something in any media app and check your status bar / lock screen / always-on-display for
   the native OriginPlayer widget.

## Verifying you have a genuine build

This app reads your notifications, so confirm any downloaded APK actually came from the maintainer.
It's signed with the **same key as origin-isle**:

```
apksigner verify --print-certs origin-ghost-player-<version>.apk
```

The output should show this fingerprint:

```
SHA-256: 5D:2D:FA:7E:F6:A9:6B:95:4A:C2:43:61:A3:30:BA:9B:2C:EA:45:18:C5:B8:63:58:C9:F4:FF:B1:1D:79:E6:00
```

If either the checksum or the certificate fingerprint doesn't match, **don't install the file** —
it isn't an official build.

## For developers

Want to build it yourself, understand the Kugou package-name spoof, or cut a signed release? See
[docs/DEV.md](docs/DEV.md).

## License

See [LICENSE](LICENSE) — a **source-available, no-redistribution** license. In short: you can read
the code and build it for your own devices, but you can't redistribute the source, modified versions,
or any build of it (signed or not) to anyone else.
