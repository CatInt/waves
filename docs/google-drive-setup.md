# Google Drive sync setup

Waves syncs subscriptions, playback progress and listening history through a single gzip-compressed JSON file
(`waves-sync.json`) in the signed-in user's Google Drive **appDataFolder**: a hidden, per-app folder that
the user can't browse and that other apps can't read. The only scope requested is
`https://www.googleapis.com/auth/drive.appdata`, plus `openid`/`email` to show which account is signed in.

You need one Google Cloud project with **two OAuth clients**: an Android client and a Desktop client.
The app builds and runs without them; sync then stays off and sign-in reports "not configured".

## 1. Create a project and enable the Drive API

1. Open <https://console.cloud.google.com/> and create a project (for example `waves-podcasts`), or pick an existing one.
2. Go to **APIs & Services → Library**, search for **Google Drive API** and click **Enable**.

## 2. Configure the OAuth consent screen (Google Auth Platform)

1. Go to **APIs & Services → OAuth consent screen**. In the newer console this is **Google Auth Platform**, with
   the tabs **Branding**, **Audience** and **Data Access**.
2. **Branding**: set the app name to `Waves`, and set a user support email and a developer contact email.
3. **Audience**: set the user type to **External**. Leave the publishing status as **Testing**.
   Under **Test users**, click **Add users** and add every Google account that will sign in, including your own.
   While the app is in Testing, only these accounts can sign in. Their refresh tokens expire after 7 days, so
   desktop users have to sign in again weekly. Publishing the app removes both limits.
4. **Data Access → Add or remove scopes**: add `.../auth/drive.appdata`, `openid` and `.../auth/userinfo.email`.
   Google classes `drive.appdata` as non-sensitive, so you don't need verification for personal use.

## 3. Android OAuth client

Android needs no client ID in code. Google identifies the app by its package name and signing certificate.

1. Get the SHA-1 fingerprint of the debug signing key:

   ```sh
   keytool -list -v -alias androiddebugkey -storepass android -keypass android \
     -keystore ~/.android/debug.keystore | grep SHA1
   ```

   Alternatively, run `./gradlew :composeApp:signingReport` and read the `SHA1` line for the `debug` variant.
   For release builds, also register the SHA-1 of your release key. If you distribute through Play App Signing,
   register the SHA-1 shown under **Play Console → App integrity**.
2. Go to **APIs & Services → Credentials → Create credentials → OAuth client ID** (or **Google Auth Platform → Clients → Create client**).
3. Set the application type to **Android**, the package name to `io.waves`, and **SHA-1** to the fingerprint from step 1. Click **Create**.
4. You don't need to copy anything into the project.

Notes:
- The device or emulator needs Google Play services and a Google account. Use a "Google Play" or "Google APIs" emulator image.
- Each signing key needs its own Android client entry, so create one for debug and one for release.

## 4. Desktop (macOS) OAuth client

1. Go to **Credentials → Create credentials → OAuth client ID** and set the application type to **Desktop app**.
   Name it `Waves macOS`, then click **Create**.
2. Copy the **Client ID** and **Client secret**. For installed apps, Google doesn't treat the secret as
   confidential, but it still must not be committed.
3. Put both values in `local.properties` at the repository root. Git ignores this file.

   ```properties
   google.desktop.clientId=1234567890-abc123.apps.googleusercontent.com
   google.desktop.clientSecret=GOCSPX-...
   ```

   For CI, you can pass them as Gradle properties (`-Pgoogle.desktop.clientId=...`) or as environment variables
   (`WAVES_GOOGLE_DESKTOP_CLIENT_ID` / `WAVES_GOOGLE_DESKTOP_CLIENT_SECRET`).
4. Rebuild. The Gradle task `:composeApp:generateGoogleOAuthConfig` writes them into
   `composeApp/build/generated/googleOAuth/.../GoogleOAuthBuildConfig.kt`, which is never committed.

The desktop flow uses OAuth 2.0 for installed apps with PKCE. The app:

1. Opens the system browser.
2. Receives the redirect on `http://127.0.0.1:<random port>`, which needs no redirect URI registration for Desktop clients.
3. Exchanges the code at `https://oauth2.googleapis.com/token`.
4. Stores the refresh token in `~/Library/Application Support/Waves/google-auth.json` with permissions `600`.

To sign out, use the app, or delete that file and revoke access at <https://myaccount.google.com/permissions>.

## 5. Wiring in code

```kotlin
// Shared
val store = DriveCloudStore(httpClient, auth)
val sync = DefaultSyncService(libraryRepository, store)
val scheduler = SyncScheduler(sync, auth, appScope).apply { start(); watchLibrary(libraryRepository) }
// Call scheduler.onResume() when the app returns to the foreground.

// Desktop
val auth = DesktopGoogleAuth(httpClient)            // uses the build-time client id/secret

// Android: create once per process, with any Context. Consent UI is shown by the built-in
// transparent GoogleAuthConsentActivity, so MainActivity needs no extra wiring.
val auth = AndroidGoogleAuth(applicationContext, httpClient)
```

## Troubleshooting

| Symptom | Fix |
| --- | --- |
| Desktop: "Google sign-in is not configured" | Add the `local.properties` keys and rebuild. |
| `access_denied` / "app has not completed verification" | Add the account under **Audience → Test users**. |
| Android: `DEVELOPER_ERROR` (status 10), or sign-in closes as soon as you pick an account | Play services reports `UNREGISTERED_ON_API_CONSOLE`. Register package `io.waves` and the SHA-1 of the key that signed the installed APK. The sign-in error includes that SHA-1. |
| Desktop asks to sign in again every week | The app is in **Testing**, so refresh tokens last 7 days. Publish the app to remove this limit. |
| Drive 403 `accessNotConfigured` | Enable the Google Drive API in the same project as the OAuth clients. |
