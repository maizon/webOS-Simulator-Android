# webOS IPK Simulator for Android

An **independent, open-source, experimental webOS TV app compatibility runtime** for Android, tuned initially to **LG UQ8050PSB / webOS TV 22**. Install a genuine `.ipk` (Debian `ar` containing `data.tar.gz` or `data.tar`) and launch HTML/JavaScript/WebAssembly homebrew from a local secure-context HTTP origin.

> **Not an LG emulator, not an LG product, and not an official webOS simulator.** Does not run LG firmware, DRM, Luna services as actual TV hardware, or native webOS binaries. Does not modify the Android MaizoSNES project or change imported IPKs.

## Features in the initial build
- **IPK library:** Storage Access Framework picker; bounded, traversal-safe extraction; reads `appinfo.json`; install, launch, uninstall. Rejects symlinks, dangerous paths, malformed metadata, and unsupported compression.
- **Per-app web sandbox:** distinct stable loopback HTTP port per app where possible, isolation by app ID, WebView with file/content access disabled, JavaScript + IndexedDB/localStorage, HTML5/JS/WebAssembly and WebGL depending on device WebView.
- **Threading experiments:** HTTP headers for COOP/COEP with opt-out, correct `application/wasm` content type. **WebView support for cross-origin isolation / SharedArrayBuffer must be validated on the device**; headers alone do not guarantee threads.
- **webOS 22 shim:** `webOS`, `webOSSystem`, `webOS.service.request`, `PalmServiceBridge`; configurable device JSON; deterministic mock Luna responses with `__simulator` labels and explicit unsupported errors.
- **Input and ROMs:** TV-style virtual navigation keys; physical Bluetooth/USB gamepads passed to WebView where supported; Android SAF HTML file chooser for ROMs/uploads. The Gamepad API and synthetic key events can vary by WebView.
- **Diagnostics:** in-app console errors, Luna API calls and unsupported methods. Chrome remote debugging is enabled only for debuggable builds.
- **CI:** GitHub Actions runs JUnit tests, builds **installable debug APK** and **unsigned release APK**. Both are published as workflow artifacts. Java-only app; Android installs the same APK on ARMv7 and ARM64 (no native .so bundled).

## How to install and test
1. Go to **Actions → Android APK → latest successful run → webos-simulator-android-apks**.
2. Download and install **app-debug.apk** on Android (enable installing apps from your browser/files app as needed). The `app-release-unsigned.apk` is **not installable until signed**.
3. Tap **Instalar arquivo .IPK** and select your MaizoSNES webOS IPK using the Android file picker.
4. Tap its entry to open. Select a game through the app's own ROM picker. Use **Logs** to identify unsupported Luna APIs, WASM or network errors.
5. If a WASM thread feature fails, try toggling COOP/COEP in Settings and compare the JavaScript console logs.

## Compatibility and known gaps
| Capability | Status |
|---|---|
| Valid web-based IPK extraction | Implemented; needs real-world testing |
| Basic HTML, JS, WebAssembly, WebAudio, WebGL | Android WebView delegates; device dependent |
| Luna connection/display/device/mock services | MOCK (not LG hardware) |
| Unsupported Luna calls | Reported as UNSUPPORTED |
| ROM picker | Android SAF through WebChromeClient |
| Bluetooth/USB gamepad | WebView support varies; no full remapper |
| LAN/WebSocket/WebRTC | Subject to Android WebView, app behavior and server availability |
| SharedArrayBuffer/threads | EXPERIMENTAL; Android WebView isolation behavior varies |
| webOS native .so / NDK binary IPKs | Unsupported and never executed |
| DRM, official LG streaming, full home launcher, proprietary TV APIs | Unsupported |
| MaizoSNES webOS end-to-end gameplay/multiplayer | **Not yet verified**; requires actual IPK + Android/TV testing |

**Security:** Import only IPKs you trust. Third-party packages run JavaScript with the WebView's allowed web privileges and network access. Isolation is best effort, not a hard multi-user security boundary. The profile is an **approximation**, not an authentic hardware identity. The emulator injects shims into HTML responses but applications with restrictive CSP, Service Workers, special navigation, custom Luna protocols, or tightly coupled native code may fail.

**Legal:** No firmware, protected ROMs, streaming DRM, or LG proprietary libraries are distributed.

## Development
```bash
gradle :app:testDebugUnitTest :app:assembleDebug
```
Requires Android SDK 35, JDK 17, Gradle 8.7; GitHub Actions supplies these. Open the project in Android Studio or run CI. Default Android WebView is not Chromium 87; the browser engine remains whatever Android supplies despite the user-agent profile.

## Next validation criteria
Use the **actual MaizoSNES webOS IPK**, inspect its `appinfo.json`, JS/WASM/thread configuration, network endpoints and Luna service usage. Launch a legally provided ROM, verify audio/video, ROM sharing, LAN and online separately on physical Android devices and the LG TV. Until then, do not claim full compatibility.

Maintainer: Maizon. Community project; no affiliation with LG.
