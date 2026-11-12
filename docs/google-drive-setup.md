# Google Drive upload – one-time Google Cloud setup

Google only lets an app reach Drive after it's registered. The app has no secrets built in: Google
recognises it by **package name + signing certificate (SHA-1)**. Do this once, in a browser, at
https://console.cloud.google.com with your Google account.

1. **Create a project** (top bar → project picker → New project), e.g. `CamLink`.
2. **Enable the Drive API**: APIs & Services → Library → "Google Drive API" → Enable.
3. **Google Auth Platform → Get started** (OAuth consent screen):
   - App name `CamLink`, your e-mail as support and contact e-mail, Audience **External**.
   - **Audience → Test users**: add every Google account that will sign in in the app.
   - **Data Access**: add the scope `.../auth/drive.file` (non-sensitive).
   - **Audience → Publish app** ("In production"), then **Verification Center** → submit for brand
     verification (needs a verified domain, home page and privacy policy). No security assessment is
     needed for `drive.file`.
4. **Clients → Create client → Android**, twice:

   | Package name          | SHA-1 certificate fingerprint                                   |
   |-----------------------|-----------------------------------------------------------------|
   | `app.camlink.usb`      | `74:A8:13:5D:A0:F9:1E:99:3E:36:06:AD:10:6A:9E:5C:F3:C8:49:1E` |
   | `app.camlink.usb.lite` | `74:A8:13:5D:A0:F9:1E:99:3E:36:06:AD:10:6A:9E:5C:F3:C8:49:1E` |

   The SHA-1 is the debug key in `%USERPROFILE%\.android\debug.keystore` that signs these APKs.
   If you ever sign with another key, add its SHA-1 too.

Nothing needs to be copied back into the app. It can take a few minutes after creating the clients
before sign-in works.

## In the app
Settings (⚙ → Upload) → **Google Drive** on → **Sign in with Google** → pick the account → allow
Drive access → **Choose** → **+ New folder in My Drive** (or a CamLink folder made earlier) → **Save**.
The app uses the limited `drive.file` scope, so it only sees folders it created. Move or share that folder
in Drive (also into a shared drive) and CamLink keeps uploading to it. FTP and Drive can both be on; every photo then goes to both.

If sign-in says "isn't set up for this app yet", step 4 is missing or the SHA-1 / package name has a typo.
