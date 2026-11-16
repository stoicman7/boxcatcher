# Bag Catcher (Android)

A lightweight Android 8.1+ prototype that detects the falling gift bags from the supplied screenshots and taps the **gift box/bow area**, not the middle of the red pouch.

## What is implemented

- Screen capture through Android `MediaProjection`.
- Two image templates: a close crop of the gift box/bow and a wider confirmation crop.
- Dependency-free normalized grayscale template matching (no OpenCV runtime dependency).
- Multiple detections per frame with non-maximum suppression. The vertical suppression distance is deliberately small so two boxes falling in the same column are not merged into one target.
- A simple downward-motion tracker to avoid tapping the same tracked bag repeatedly.
- One normal single-finger tap at a time through the enabled Android AccessibilityService.
- A foreground notification with a Stop action.
- A transparent touch-through debug overlay that draws the detector's matched template rectangles and the exact next-tap crosshair.
- Live on-device tuning for confidence, scan precision, frame interval, fall speed, prediction lead, tap duration, and X/Y tap offsets.
- Separate counters and an exportable event log for detections, tap attempts, Android dispatch acceptance/rejection, gesture completion/cancellation, and frame errors.
- A 360-pixel-wide working image and a restricted main-play-area region to avoid the bottom toolbar and chat controls.

## Important limitations

This is a **first working prototype source project**, not a finished or field-tested APK. The detection thresholds were calibrated against the two supplied still screenshots and the video measurements quoted in the conversation. They have not been benchmarked on the actual Android 8.1 phone or tested against the full recording frame-by-frame.

The detector currently uses these initial settings:

- Approximate falling speed: 1,080 screen pixels/second.
- Target area: gift box/bow, with the tap point near its centre and a small 12 ms downward lead.
- Working width: up to 360 pixels.
- Frame processing throttle: about 18 frames/second (the device may deliver fewer).
- Template score thresholds: 0.74 for the core gift-box crop and 0.78 for the wider crop.
- Tap area: central play region, excluding the top edge and lower toolbar/chat region.
- Each detected gift box is sent as an individual single-finger gesture; the lowest eligible box is handled first.
- Live settings are read during capture, so changing a slider does not require restarting the session.

Because this uses image matching, it can still miss boxes or occasionally match a visually similar object. Test it in a low-stakes round first. A different device resolution, game update, UI layout, or overlay may require recalibration.

## Live calibration (no repeated APK downloads)

Install the debug APK once. During a game session, tap the floating **BC** circle and choose **OPEN DEBUG SETTINGS**. You can change the tuning values while capture is running; they are saved on the phone and applied on the next processed frame.

Recommended calibration order:

1. A new session starts with auto taps paused. Detection and the overlay continue running.
2. Check the cyan rectangles. They should surround the gift-box pattern, not unrelated graphics. Lower confidence if real boxes are missed; raise it if unrelated objects are marked.
3. Check the red **NEXT TAP** crosshair. Adjust horizontal/vertical offset until it sits on the cream box area. Positive Y moves down; negative Y moves up.
4. Only after the target is correctly positioned, activate tapping and tune prediction lead and gesture duration.
5. If the phone becomes slow, increase the frame interval or scan step. A scan step of 1 is more thorough but uses more CPU.
6. Use **Copy diagnostic report** or **Share diagnostic report** to send the exact counters and recent events after a failed attempt.

The diagnostic report deliberately separates an attempted tap, Android accepting the dispatch, and Android's completed/cancelled gesture callback. A completed callback does **not** prove that the game registered the touch.

## Learning from your own taps

Bag Catcher has two supervised learning modes available from the floating **BC** menu:

- **Learn from video:** tap the gift boxes shown in a playing video. The app records the screen crop around each tap but does not forward the touch to the video player, so playback continues.
- **Learn in game:** tap the gift boxes yourself. The app records each positive example and replays the same touch gesture into the game through the Accessibility service.

Both modes pause automatic tapping. Only short taps are treated as positive bag examples; swipes are not used as labels. The app stores up to 50 distinct grayscale examples privately on the device and uses the four most recent examples as additional detector templates to limit CPU load. The detector rebuilds its learned templates automatically after a new example is saved. The app includes undo-last and clear-all controls in the learning section of its settings screen.

Learning is local to the device. It is an adaptive template-matching system, not a cloud-trained neural network. A tap is treated as a positive label because the user deliberately selected it, so only tap actual gift boxes while learning. If a wrong object is taught, undo that example.

## Build the APK with GitHub Actions

This ZIP includes `.github/workflows/android.yml`. GitHub Actions builds a debug APK and stores it as an artifact.

1. Create a GitHub repository for this project.
2. Extract this ZIP on your computer.
3. Upload the **contents** of the extracted `BagCatcher_Android_Project` folder to the repository root (including the hidden `.github` folder).
4. Commit the files to `main` or `master`, or open the repository's **Actions** tab and run **Build Bag Catcher APK** with **Run workflow**.
5. Open the completed workflow run. Under **Artifacts**, download `BagCatcher-debug-apk` and extract `app-debug.apk`.
6. Transfer `app-debug.apk` to the phone and install it. Android may ask you to allow installation from that file manager/browser.

The workflow uses Java 17, Gradle 8.9, Android Gradle Plugin 8.7.3, and Android SDK platform 35. No third-party Android libraries are required.

## Install and use

1. Open the game first.
2. Open Bag Catcher (launch it from Recent Apps so the game remains behind it).
3. Tap **Enable automatic tapping** and enable Bag Catcher in Android Accessibility settings.
4. Return to Bag Catcher and tap **Start screen detection**.
5. Approve Android's screen-capture prompt. Bag Catcher sends itself behind the current task so the game can be visible again.
6. Keep the game in the foreground during the round. Use the ongoing notification's **Stop** action or reopen Bag Catcher and tap **Stop detection** to stop.

Android requires the user to approve screen capture for every new capture session. Accessibility permission is separate and is normally enabled only once.

## Technical notes

- Minimum Android version: Android 8.0 (API 26); intended to run on Android 8.1.
- The screen is downsampled using area averaging before matching.
- Both templates are centered around the visible cream gift box and peach bow. The wider template provides a second match shape; non-maximum suppression merges duplicate matches from the two templates while preserving vertically separated boxes.
- The tap service dispatches one 75 ms single-finger stroke at a time. A tiny 1 px path segment gives Android a non-zero gesture path while keeping the touch effectively stationary. Verify touch registration on the actual game/device.
- This tool only performs local screen analysis and taps. It does not log in, send messages, or access game account data.

## Project structure

```text
app/src/main/
  AndroidManifest.xml
  java/com/example/bagcatcher/
    MainActivity.kt
    CaptureService.kt
    BagDetector.kt
    BagTracker.kt
    TapAccessibilityService.kt
  res/xml/accessibility_service_config.xml
  assets/giftbox_core.png
  assets/giftbox_wide.png
.github/workflows/android.yml
```
