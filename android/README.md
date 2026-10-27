# PDF Utils for Android

A native Android client for the PDF Utils API. It has the same 12 tools as the web app, plus history and accounts. Package: `site.chirudev.pdfutils`.

- **UI:** Kotlin, Jetpack Compose and Material 3, using the web app's palette (`ui/theme/Theme.kt`). It adapts to the system light or dark theme and lays out for phones, foldables and tablets.
- **Networking:** OkHttp, Retrofit and kotlinx.serialization. Uploads stream straight from the content Uri, and the single-flight token refresh lives in `core/data/SessionManager.kt`.
- **Previews:** the platform `PdfRenderer`. There is no bundled PDF engine, which keeps the release APK at about 3 MB.
- **Dependency injection:** Hilt.
- **Battery:**
  - Job polling pauses while the app is in the background.
  - There are no background services or timers. The access token is refreshed only when a request needs it.
  - Thumbnails are small and cached in memory.

## Layout

| Path | What lives there |
| --- | --- |
| `core/network` | API models, `ApiService`, error mapping, auth interceptor/authenticator, streaming upload body |
| `core/data` | Session and refresh-token storage (Android Keystore, AES-GCM), `PdfRepository` (upload → task → poll → download), account |
| `core/pdf` | Page thumbnails, page-range parsing |
| `core/files` | Saving to Downloads, Save as…, share, open |
| `tools` | Tool registry, a 1:1 port of `frontend/src/lib/tools.ts`, plus the tool screen and ViewModel |
| `home`, `history`, `account`, `auth`, `navigation` | Screens |

## Build and run

You need JDK 17 or newer and the Android SDK. Android Studio's bundled JBR works.

```sh
cd android
./gradlew installDebug          # debug build (app id site.chirudev.pdfutils.debug) against production
./gradlew testDebugUnitTest     # unit tests (JVM + Robolectric)
./gradlew lintDebug
```

To point a build at a local backend (`make dev`), add `-PAPI_BASE_URL=http://10.0.2.2:8080/` from the emulator. Cleartext traffic is allowed only to `10.0.2.2` and `localhost`.

## CI and releases

`.github/workflows/android.yml` runs on every push and pull request that touches `android/**`. Each run does lint, unit tests, and the release APK and AAB.

On `main` it also creates a GitHub Release `android-v1.0.<run>` with `pdf-utils-1.0.<run>.apk` and the `.aab` attached. The `versionCode` is the workflow run number.

### One-time signing setup

1. Run `android/scripts/make-keystore.sh`. It creates `~/.android-keys/pdf-utils-release.jks` and prints the `gh secret set` commands. Back the file and its password up, because every future Play update must be signed with this key.
2. Add the four secrets it lists:
   - `ANDROID_KEYSTORE_BASE64`
   - `ANDROID_KEYSTORE_PASSWORD`
   - `ANDROID_KEY_ALIAS`
   - `ANDROID_KEY_PASSWORD`

Until these secrets exist, `main` builds are debug-signed and published as prereleases.

### Google sign-in (optional)

1. In Google Cloud Console → Credentials, create an **Android** OAuth client:
   - package `site.chirudev.pdfutils`
   - the SHA-1 printed by the script

   If you use Play App Signing, also add the SHA-1 of Play's app-signing key.
2. Set the repository variable `ANDROID_GOOGLE_WEB_CLIENT_ID` to the **web** client ID, which is the backend's `GOOGLE_CLIENT_ID`. Credential Manager issues ID tokens for that audience, and the backend already accepts it, so `GOOGLE_MOBILE_CLIENT_IDS` doesn't need changing. The Android client from step 1 only has to exist in the same Google Cloud project.

The button only appears when the variable is set and `/v1/config` reports `google_auth: true`.

### Email links open in the app

Verify-email and reset-password links (`https://pdf-utils.chirudev.site/verify-email?token=…`) open in the app once the domain serves Digital Asset Links:

1. Copy `assetlinks.json.example` to `frontend/public/.well-known/assetlinks.json`.
2. Put in the SHA-256 printed by the script. With Play App Signing, use Play's app-signing key SHA-256 too.
3. Deploy the web app.

Caddy serves it as a static file.

## Publishing to Google Play

Upload `pdf-utils-<version>.aab` from the release to the Play Console under the developer account for chirudev.site, with Play App Signing on (the default). The app needs no runtime permissions:

- Files come in through the system picker or the share sheet.
- Results go to `Downloads/PDF Utils` through MediaStore.

That keeps the Data safety form simple. Files are sent to the PDF Utils server for processing and deleted on its retention schedule.
