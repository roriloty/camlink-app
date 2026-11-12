# CamLink

Android apps for tethering a Sony Alpha camera (tested on the A7 III) over a USB-C cable.

- **CamLink USB**: live view, tap-to-focus with the camera's own AF frame, exposure controls
  (aperture, shutter, ISO, EV, white balance, focus mode, AF area), remote shutter, and an in-app viewer.
- **CamLink Lite**: no live view, for long shoots on battery. It runs in the background, receives every
  photo you take with the camera's shutter, and can keep going with the phone locked.

Both save the camera's original files to `Pictures/CamLink` and can optionally:

- **Auto-edit** JPEGs before upload with a small on-device algorithm that applies the author's Lightroom style
  (highlights kept, whites and blacks set, shadows lifted, contrast kept, night scenes left dark).
- **Upload** to an FTP/FTPS server and/or a CamLink folder in Google Drive (limited `drive.file` access; move the
  folder into a shared drive or share it if you like).
  You can send the edited copy, the original, or both, put them in subfolders, and delete the phone copy afterwards.
- Show an **upload gallery** with previews. You can delete a photo you don't want from the queue or after upload
  (phone, FTP, and Drive trash).

## Install
Download the APKs from [Releases](../../releases). Then set the camera to
**MENU → Setup → USB Connection → PC Remote** and **PC Remote Settings → Still Img. Save Dest. → PC+Camera**,
and plug it in.

## Build
```
./gradlew assembleFullRelease assembleLiteRelease
```
Google Drive sign-in needs an Android OAuth client for your own signing key. See
[docs/google-drive-setup.md](docs/google-drive-setup.md).

## Privacy
Everything runs on the phone; there is no CamLink server. See the [privacy policy](https://roriloty.github.io/camlink-app/privacy.html).
