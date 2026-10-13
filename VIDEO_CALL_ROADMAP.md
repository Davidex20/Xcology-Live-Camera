# Video-call integration — engineering scope

**Current status: unimplemented.** No virtual camera, no WhatsApp or Google Meet camera override, and no WebRTC calls in v0.3.

## Supported route: integrate processed frames into our own call experience

1. Introduce a `ProcessedFrameSink` abstraction fed by the same `Bitmap` delivered to `ImageView` and `ProcessedVideoRecorder`.
2. Add a separate `:calls` module with a supported WebRTC Android SDK, permission handling, peer-connection signaling, audio capture, call lifecycle, and encryption.
3. Move the image processing from CPU to a GPU-backed rendering path to avoid multi-second lag and huge battery drain when recording and streaming simultaneously.
4. Convert processed bitmap frames into the WebRTC encoder's expected native `VideoFrame` buffers with correct timestamps/rotation, without sending unfiltered frames.
5. Run an optional local test-call loopback between two user-controlled devices before enabling remote signaling. Clearly show when camera and microphone are transmitting.

## Third-party video calls

Android typically isolates camera access per app. This standalone camera app **cannot** be selected as a replacement camera in WhatsApp or Google Meet on a stock Pixel 9 Pro merely by installing an APK. Do not promise a "virtual camera" without a specific supported system/OEM mechanism and end-to-end device verification. Some calling applications allow *screen sharing* of the camera app, but that isn't a real virtual camera.

## Production readiness

Needs device tests for segmentation quality across skin tones and lighting, measured preview FPS, thermals, codec support, audio/video sync, battery use, network interruptions, echo cancellation, network privacy, and user consent indicators.
