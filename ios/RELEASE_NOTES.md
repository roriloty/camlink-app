CamLink for iPhone & iPad. **Unsigned IPA**: you sign it yourself when installing (see below).

### New in 1.13.0 (same version and features as the Android app)
- **Startup question** "How do you want to use the camera?": Camera control or Upload only, with a *last used* badge,
  "Always start in the mode I pick", and a **Change mode** button in both screens that releases the camera and asks again.
- **Settings** laid out like Android: FTP server, Google Drive, Editing and upload, Subfolders, On the phone, with a pinned
  Save button and a "To FTP + Google Drive · auto-edit" summary.
- **Upload gallery** with Queue / Uploaded tabs, Clear list, Retry, and Delete everywhere (phone, FTP, Drive trash) with confirmation.

### Earlier (1.11.2)
- **Google Drive sign-in works on iOS** (Settings › Google Drive › Sign in with Google; **Switch** picks another account).
  CamLink only gets access to the folders and files it creates (drive.file).
- **Save edited copy to phone** (Settings): every JPEG is auto-edited and the edited copy is saved to the **CamLink** album
  in Photos, even when nothing is uploaded. The original is saved as before. "Auto-edit JPEGs" still only decides what's uploaded.

### Install
1. On a Windows PC or Mac, install [Sideloadly](https://sideloadly.io) (or AltStore / AltServer).
2. Connect the iPhone/iPad, open Sideloadly, drop in `CamLink-iOS-…-unsigned.ipa`, enter your Apple ID and click Start.
3. On the device: Settings › General › VPN & Device Management › trust your Apple ID.
   On iOS 16+ also turn on Settings › Privacy & Security › **Developer Mode** (the device restarts).
4. With a free Apple ID the app stops opening after **7 days**; re-install (re-sign) it with Sideloadly. A paid Apple Developer account
   ($99/year) signs for a year and allows TestFlight.

### Use
Camera: MENU › Setup › USB Connection › **PC Remote**, and PC Remote Settings › Still Img. Save Dest. › **PC+Camera**.
Connect with a USB-C cable (Lightning iPhones: Apple's Lightning to USB 3 Camera Adapter). Allow "control cameras" when iOS asks.
At first start choose **Camera control** (live view, tap to focus, aperture/shutter/ISO/EV/WB/focus mode/AF area, shutter)
or **Upload only** (receive, auto-edit and upload every photo you shoot with the camera's own button).

### What's different from Android
- USB goes through Apple's ImageCaptureCore (iOS has no raw USB). If connecting fails, open Settings › Debug log and send the log.
- **No background tether.** iOS pauses USB (and uploads, after ~30 s) when CamLink isn't on screen or the device locks.
  Upload-only mode keeps the screen awake and has a *Dark screen* button to save battery.
- Photos are saved in Files › On My iPhone/iPad › CamLink, and also added to Photos (switchable).
  "Delete after upload" removes the CamLink copy; the Photos copy stays.
- FTP: passive mode only. FTPS works with most servers; servers that demand TLS session reuse on the data connection may refuse it.
- Auto-edit is the same algorithm as Android, verified pixel-identical on test images.
