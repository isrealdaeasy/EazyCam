# EazyCam — Android camera project

EazyCam is a Kotlin + CameraX camera app starter for Android, designed around an iPhone-inspired look with Vivid, Clean White, Black & White, Natural, Cinematic, and Warm photo profiles.

## Features in this implementation
- CameraX preview and max-quality still capture
- Front/rear camera switching
- Tap-to-focus and metering
- Zoom presets and flash toggle
- Exposure compensation control
- Runtime camera capability detection for manual ISO and shutter speed
- White balance presets
- JPEG post-processing and saving to `Pictures/EazyCam`

## Build an APK
This workspace did not have the Android SDK or Gradle installed, so an APK could not be compiled or device-tested here. Open this folder in Android Studio on a computer with Android SDK Platform 35 installed, allow Gradle sync, then choose **Build > Build Bundle(s) / APK(s) > Build APK(s)**. The debug APK should appear at `app/build/outputs/apk/debug/app-debug.apk`.

## Compatibility notes
- Manual ISO/shutter availability depends on the Redmi 14C camera's Camera2 characteristics. EazyCam checks those characteristics and falls back to auto exposure when unavailable.
- The current filter pipeline processes captured JPEGs after capture. Live preview filter rendering, RAW capture, and a dedicated gallery screen are planned enhancements, not included in this first implementation.
- Test in Android Studio on the target phone before distributing. This is a starter build and has not yet been compiled or validated on-device in this environment.

## Build in the cloud (useful if you only have an Android phone)
The repository includes `.github/workflows/android-apk.yml`. Upload this project to a GitHub repository, then open **Actions → Build EazyCam APK → Run workflow**. When the run succeeds, download the `EazyCam-debug-apk` artifact and install the APK on your phone. GitHub Actions must be enabled for the repository. The first build may reveal dependency or SDK issues that need fixing; this workflow has not been run in this environment.
