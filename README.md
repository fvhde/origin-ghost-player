# Origin Ghost Player
<div align="center">

[![Release](https://img.shields.io/github/v/release/fvhde/origin-ghost-player)](https://github.com/fvhde/origin-ghost-player/releases)
[![Downloads](https://img.shields.io/github/downloads/fvhde/origin-ghost-player/total)](https://github.com/fvhde/origin-ghost-player/releases)

</div>

<div align="center">
  
Shows OriginOS's native **OriginPlayer** widget for *any* media app, not just whitelisted ones.
Runs invisibly in the background, and widget controls go straight to the real app.

<sub>Hobby project, not affiliated with vivo, iQOO, OriginOS, Kugou or Luna.</sub>

**Credits** · OriginIsland doc api by [@theVakhovskeIsTaken](https://github.com/theVakhovskeIsTaken)

**Support** · [![Buy Me a Coffee](https://img.shields.io/badge/Buy%20me%20a%20coffee-FFDD00?logo=buymeacoffee&logoColor=black)](https://buymeacoffee.com/fvhde)

</div>

> [!WARNING]
> **vivo/iQOO + OriginOS only.**

## Download

| Build | Imitates | Why pick it |
|-------|----------|-------------|
| **Kugou** | `com.kugou.android.lite` | Most tested |
| **Luna** | `com.luna.music` | Bigger lockscreen widget, karaoke |

Get it from **[Releases](https://github.com/fvhde/origin-ghost-player/releases)**, install, open it, and grant:
- **Notification access** (required)
- **Unrestricted background** (recommended)

<details>
<summary><b>Verify your download</b></summary>

```sh
shasum -a 256 origin-ghost-player-<variant>-<version>.apk   # must match the .sha256 file
apksigner verify --print-certs origin-ghost-player-<variant>-<version>.apk
```
Expected certificate:
```
5D:2D:FA:7E:F6:A9:6B:95:4A:C2:43:61:A3:30:BA:9B:2C:EA:45:18:C5:B8:63:58:C9:F4:FF:B1:1D:79:E6:00
```
Doesn't match? **Don't install.**
</details>

<details>
<summary><b>Developers</b></summary>

Building, the package spoof, and signing: [docs/DEV.md](docs/DEV.md)
</details>

---
**License** · Source-available, no redistribution. See [LICENSE](LICENSE)
