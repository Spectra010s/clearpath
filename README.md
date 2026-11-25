# ClearPath

ClearPath is a voice-first, blind-first Android assistive companion powered by Jetpack Compose, Firebase AI Logic (Gemini Live audio & 1 FPS camera streaming), CameraX, ML Kit OCR, GPS/Compass sensors, and OpenStreetMap routing.

- **Application ID:** `com.clearpath.assistant`
- **Gradle:** `9.3.1` (pinned in `gradle/wrapper/gradle-wrapper.properties`)
- **Android SDK:** `compileSdk 36`, `targetSdk 36`, `minSdk 24`

---

## Prerequisites

1. **JDK 21** (Temurin / Adoptium recommended)
2. **Android SDK** with Platform 36 and Build-Tools installed (`ANDROID_HOME` or `ANDROID_SDK_ROOT` set, or `sdk.dir=...` in your local `local.properties` — ignored by Git)
3. **Firebase configuration:** `app/google-services.json` is already included for `com.clearpath.assistant` (using Firebase AI Logic + App Check; no `GEMINI_API_KEY` needed).

---

## Building the Release APK

### 1. Create a release signing keystore (one-time)

`app/build.gradle.kts` signs release builds using the `upload` key alias and environment variables (`KEYSTORE_PATH`, `STORE_PASSWORD`, `KEY_PASSWORD`):

```bash
keytool -genkeypair -v \
  -keystore my-upload-key.jks \
  -storetype PKCS12 \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -alias upload
```

> Keep `my-upload-key.jks` private. `*.jks` and `*.keystore` are excluded in `.gitignore`.

### 2. Build the signed release APK

**macOS / Linux:**

```bash
export KEYSTORE_PATH="$(pwd)/my-upload-key.jks"
export STORE_PASSWORD="your-store-password"
export KEY_PASSWORD="your-key-password"

./gradlew assembleRelease
```

**Windows (PowerShell):**

```powershell
$env:KEYSTORE_PATH = "$PWD\my-upload-key.jks"
$env:STORE_PASSWORD = "your-store-password"
$env:KEY_PASSWORD = "your-key-password"

.\gradlew.bat assembleRelease
```

The signed release APK will be output to:

```text
app/build/outputs/apk/release/app-release.apk
```

---

## Installing the Release APK

### Option A: Install via ADB (USB or Wireless Debugging)

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

### Option B: Install directly on the phone

1. Copy `app/build/outputs/apk/release/app-release.apk` to your Android device (or download the artifact from the GitHub Actions **Build Release APK** workflow).
2. Open the `.apk` file in your device's file manager.
3. If prompted, allow **Install unknown apps** for that file manager/browser, then tap **Install**.

---

## GitHub Actions CI (`.github/workflows/release-apk.yml`)

Every push to `main` (or manual workflow dispatch) builds the release APK and uploads it as a downloadable workflow artifact (`clearpath-release-apk`).

To sign CI builds with your own release keystore, add these **Repository Secrets** in GitHub (**Settings -> Secrets and variables -> Actions**):

- `KEYSTORE_BASE64`: Base64-encoded contents of your `my-upload-key.jks` (must use key alias `upload`)
  - macOS/Linux: `base64 -w 0 my-upload-key.jks` (macOS: `base64 -i my-upload-key.jks | tr -d '\n'`)
- `STORE_PASSWORD`: Your keystore password
- `KEY_PASSWORD`: Your key password
- `GOOGLE_SERVICES_JSON_BASE64` *(optional if `app/google-services.json` is not committed in GitHub)*: Base64-encoded contents of `app/google-services.json`

If signing keystore secrets are not configured yet, the workflow generates an ephemeral CI keystore (`alias=upload`) so builds still succeed for testing. If your GitHub export omits `app/google-services.json`, either add the file to `app/google-services.json` in your repo or set `GOOGLE_SERVICES_JSON_BASE64`.
