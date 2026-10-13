# Xcology Live Camera — v0.4 multi-class skin AI (EXPERIMENTAL)

This is an **Android source project**, not a prebuilt APK. It is specifically intended to produce a live camera skin-tone filter which can lighten dark skin to a lighter/fair appearance while attempting to leave eyes, hair, clothing and background unchanged. Pixel 9 Pro is the test target; **the APK has not yet been compiled or hardware-tested** in this environment.

## Actual implementation

- CameraX live front/rear RGB frames, correct rotation and mirrored front preview.
- **MediaPipe SelfieMulticlass image segmentation**. The AI model predicts six pixel categories: background (0), hair (1), body skin (2), face skin (3), clothes (4) and accessories (5). **Only face/body skin categories (2/3) receive the primary color transformation.** This is a significant improvement over generic person detection + color thresholding.
- GPU delegate attempted first; CPU fallback if the GPU delegate isn't supported. If multiclass model initialization or inference fails, the app falls back to bundled ML Kit person segmentation + a color-based skin estimator. The UI indicates fallback mode.
- ML Kit face landmarks and cheek color reference, with conservative preservation regions around the eyes and mouth.
- Intensity, target lightness (tan → fair), and warm/cool undertone sliders.
- On-device processing and real-time filtered preview, local JPG photo export to `Pictures/Xcology`.
- Encoder/muxer for silent filtered H.264 MP4 recordings, limited to 15 input FPS, saved to `Movies/Xcology`; "Share last video" button.
- Automated GitHub Actions Android debug APK build configuration.

## Model packaging

The repository **does not contain the 16.4 MB model binary**. `app/build.gradle.kts` automatically downloads the official MediaPipe SelfieMulticlass model at build time to `app/src/main/assets/selfie_multiclass_256x256.tflite` and verifies SHA-256 `c6748b1253a99067ef71f7e26ca71096cd449baefa8f101900ea23016507e0e0`. The model is then bundled inside the APK. Build requires internet, but the finished app's segmentation does not.

Official documentation: https://developers.google.com/edge/mediapipe/solutions/vision/image_segmenter

## Important limitations

- This is working **source code under development**, not a verified finished app. Android/Gradle compilation hasn't been run; real camera, encoder, GPU delegate, and device compatibility tests are still required.
- Semantic skin detection is based on model predictions and **can mislabel pixels** (ears/hands, occlusions, low light, fast motion). No guarantee of a photorealistic transformation in every scene.
- **Video recording is silent** (no microphone recording and no sound track), and 15 FPS is a target encoding limit, not an observed live processing speed.
- Although a GPU delegate is attempted for the model, the tone mapping itself still uses a **CPU pixel processing loop**. A dedicated GPU rendering pipeline is future work.
- **Video-call integration is NOT done**. Stock Android cannot generally expose the output of this app as a replacement camera in WhatsApp or Google Meet. A future supported approach is custom WebRTC in-app video calling using processed frames. See `VIDEO_CALL_ROADMAP.md`.

## Building an APK

Use JDK 17 and Gradle 8.9 with Android SDK 35 + build-tools 35.0.0. Gradle must be able to reach Google's Maven and model hosting endpoints.

Option A — GitHub: extract this ZIP, upload its **entire directory tree** to your repository (do **not** upload the ZIP by itself), and run Actions → **Build Xcology APK**. After a successful run, download the `Xcology-v0.4-debug-apk` artifact and extract the `app-debug.apk` inside it.

Option B — Android Studio: open the project root, allow Gradle sync/model download, build a debug APK, then transfer it to your Pixel 9 Pro.

Run the included pure Kotlin color mapping unit test if available (`tests/SkinToneEngineTest.kt`).

All filtering occurs on device with bundled AI models; the AndroidManifest requests camera permission but does not request INTERNET permission. Saving to MediaStore occurs only when you press Photo or Record.
