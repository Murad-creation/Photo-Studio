# Smart Scan & Photo Studio

Scan. Edit. Save. Share.

## Versions
| Version | Contents |
|---|---|
| 0.1.0 | Design system, light/dark/system theme (DataStore), navigation, Home dashboard |
| 0.2.0 | NID Scanner: CameraX capture, gallery import, OpenCV card detection + manual corner adjustment, perspective correction, Original / Auto / Magic Color, brightness / contrast / sharpening, rotate, A4 layout (0.5 in gap), PDF / JPG / PNG export, Save to device, Share |

The version is shown in Settings > About (from `versionName` in `app/build.gradle.kts`).

## Not built yet
Document Scanner, Passport Photo Studio, Send to PC, My Files and the remaining settings still show a "Not built yet" screen. The "Send to PC" button in NID export is disabled until the transfer phase.

## Known limits (0.2.0)
- The camera overlay is a positioning frame. Card edges are detected after capture, then you can drag the four corners.
- NID progress is kept while the app is alive, not after the system kills the process.
- PDF size at 300 DPI can be several MB (Android's PdfDocument does not compress images much).

## Build the APK
1. Create a GitHub repository and upload all files of this project (including the hidden `.github` folder).
2. Open the **Actions** tab, run **Android Build** (it also runs on every push).
3. When it finishes, open the run and download the **app-debug** artifact. It contains the `.apk`.
4. If the build fails, open the red step and copy the error lines.

Local build: JDK 17, Android SDK 34, Gradle 8.9 (`gradle assembleDebug`).

## Privacy
All processing is on-device. No analytics, no ads, no uploads. The only permission is CAMERA.
