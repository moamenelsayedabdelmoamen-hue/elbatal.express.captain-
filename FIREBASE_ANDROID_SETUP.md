# Firebase Android configuration

The Captain Android application must use the registered Firebase **Android** app for this exact package:

`com.aistudio.elbatalexpresscaptain.captain`

The expected Firebase project is `el-batal-express-956ae` and the Storage bucket is
`el-batal-express-956ae.firebasestorage.app`.

## Local setup

1. Open Firebase Console → project `el-batal-express-956ae` → Project settings → Your apps.
2. Select the Android app whose package name is exactly `com.aistudio.elbatalexpresscaptain.captain`.
3. Download its genuine `google-services.json` file.
4. Place it at `app/google-services.json` (do not rename it and do not use a Web app configuration).
5. Run `gradle :app:processDebugGoogleServices :app:testDebugUnitTest :app:assembleDebug`.

Do not fabricate the Android `mobilesdk_app_id` or substitute the Web app ID from
`firebase-applet-config.json`. The Android registration must exist in Firebase Console.

## GitHub Actions setup

The CI workflow reads the same genuine file from a repository Actions secret so the
file does not need to be committed.

On Windows PowerShell, from the repository root:

```powershell
$bytes = [System.IO.File]::ReadAllBytes(".\app\google-services.json")
$base64 = [Convert]::ToBase64String($bytes)
gh secret set GOOGLE_SERVICES_JSON_BASE64 --repo moamenelsayedabdelmoamen-hue/elbatal.express.captain- --body $base64
```

Alternatively, create a repository secret named `GOOGLE_SERVICES_JSON_BASE64` in
GitHub → Settings → Secrets and variables → Actions, and paste the Base64 value.

The workflow validates the Firebase project ID, Storage bucket, Android package name,
and Android app registration before compiling or testing. The Gradle Google Services
plugin is configured to fail when the configuration is missing; this prevents a
misconfigured APK from being reported as a successful build.

Never commit service-account JSON files or private signing keys. The Google Services
client configuration is not a service-account credential, but this project keeps it
out of Git and supplies it to CI through the protected repository secret.
