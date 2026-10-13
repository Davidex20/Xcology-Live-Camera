# Xcology Live Camera — Android prototype

A starter Android CameraX app with live skin-tone lightening, adjustable slider, front/back switching, and photo capture.

## Build
1. Open this folder in Android Studio (JDK 17, Android SDK 35).
2. Allow Gradle sync to download dependencies.
3. Build > Build APK(s), then install the debug APK on an Android phone (Android 8+).
4. Grant camera permission.

## Important limitations
- This is an **uncompiled source prototype**, not a tested APK.
- The chroma-threshold filter is **not ML segmentation**. It can recolor background objects and miss skin in certain lighting. Skin texture and realism will vary.
- Frame conversion and CPU filtering are not optimized; smooth 30 FPS is **not guaranteed**.
- The app supports photo capture only, not video recording or use as a virtual camera in other apps.
- Captures are saved to the app's external Pictures directory, which may require a file manager to access.

## Production roadmap
Replace thresholding with a trained skin segmentation model, feather mask boundaries, implement GPU shaders, add CameraX VideoCapture, test frame rates and color accuracy across diverse lighting, and add export via MediaStore.

## Build APK using GitHub Actions (no Mac required)
1. Create a new private GitHub repository.
2. Upload **the contents of this `XcologyLiveCamera` folder** to the repository root, including `.github/workflows/android-apk.yml` (hidden folder).
3. Go to Actions > Build Xcology APK > Run workflow.
4. After a successful run, open the run and download the `Xcology-Live-Camera-debug-apk` artifact. Unzip it and install the APK on Android.

This workflow has not been run or validated in this environment. It creates a debug APK, not a Play Store release.
