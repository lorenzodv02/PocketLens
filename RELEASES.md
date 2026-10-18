# PocketLens releases

## v1.0.0

The repository's `v1.0.0` artifacts are the requested latest builds:

| Platform | File | Build |
| --- | --- | --- |
| Windows x64 | [`PocketLens.exe`](releases/v1.0.0/PocketLens.exe) | Portable Electron app with the orientation-restored virtual camera. |
| Android | [`PocketLens-android-debug.apk`](releases/v1.0.0/PocketLens-android-debug.apk) | Debug APK with native 16:9 selection, 4:3-to-16:9 crop fallback, and screen-off streaming support. |

### SHA-256

| File | SHA-256 |
| --- | --- |
| `PocketLens.exe` | `3DAA29F6E4027B53CA7C9CF16F92CE9472870DA6A1EFB50756F7013ACAA4335E` |
| `PocketLens-android-debug.apk` | `C37C9BBD087FFD740E7EFE59C4B66D98F9B7D29364524E16579BB2A2E2ACAD6D` |

Verify downloads in PowerShell with:

```powershell
Get-FileHash .\PocketLens.exe -Algorithm SHA256
Get-FileHash .\PocketLens-android-debug.apk -Algorithm SHA256
```

### Installation

- **Windows:** Run `PocketLens.exe`. On first use of the virtual camera, install
  it from the PC app and approve the administrator prompt. Restart applications
  using the camera after installing an update.
- **Android:** Install the APK and grant camera permission when prompted. Put
  the phone and PC on the same trusted private Wi-Fi network. The debug APK is
  not a Play Store release.

This project accepts camera streams from the local network without pairing or
authentication. Use it only on a trusted network; see the README's privacy
section.
