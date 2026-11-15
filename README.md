# Smart Scan & Photo Studio

Scan. Edit. Save. Share.

## Versions
| Version | Contents |
|---|---|
| 0.1.0 | Design system, light/dark/system theme, navigation, Home dashboard |
| 0.2.0 | NID Scanner: CameraX capture, gallery import, OpenCV card detection + draggable corners, perspective correction, Original / Auto / Magic Color, A4 layout with 0.5 in gap, PDF / JPG / PNG export |
| 0.3.0 | Document Scanner: multi-page capture, filters (Original, Magic Color, Grayscale, Black & White), reorder / delete / retake, paper sizes + orientation, PDF preview, PDF / JPG / PNG export |
| 0.9.0 | Passport Photo Studio (ML Kit face detection + selfie segmentation, background colors, size presets, print sheets), Send to PC (temporary local Wi-Fi server, QR code), My Files (search, sort, filter, grid/list, rename, delete, share, favorites, details, preview), full Settings, Home recent items. Release candidate: all screens are wired, not yet verified on a device |

The version is shown in Settings > About (from `versionName` in `app/build.gradle.kts`).

## Known limits
- Edge detection runs after capture (no live tracking or auto shutter). You can drag the four corners.
- Background removal uses ML Kit selfie segmentation; hair edges can be imperfect. Face identity is never altered, only background, framing and light.
- Send to PC works only while the app stays open and both devices are on the same network. The session stops on timeout, on download completion or with the Stop button.
- Scan progress is kept while the app is alive, not after Android kills the process.
- Saved files live in the app's private library; use "Save to device" or Share to copy them out.

## Build the APK
1. Create a GitHub repository and upload all files of this project (including the hidden `.github` folder).
2. Open the **Actions** tab and run **Android Build** (it also runs on every push).
3. When it finishes, open the run and download the **app-debug** artifact. It contains the `.apk`.
4. If the build fails, open the red step and copy the error lines (lines starting with `e:`).

Local build: JDK 17, Android SDK 34, Gradle 8.9 (`gradle assembleDebug`). No Gradle wrapper is included; the workflow installs Gradle 8.9 itself.

## Privacy
All processing is on-device. No analytics, no ads, no uploads. Permissions: CAMERA, and INTERNET / ACCESS_NETWORK_STATE only for the temporary local transfer server.
