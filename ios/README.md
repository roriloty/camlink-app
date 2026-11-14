# CamLink for iPhone & iPad

SwiftUI version of the Android app (universal, iOS/iPadOS 16+). At first start it asks which mode to run:

- **Camera control**: live view, tap to focus with the camera's AF frame, aperture / shutter / ISO / EV / white balance /
  focus mode / AF area, shutter button, in-app viewer.
- **Upload only**: no live view; receives every photo shot with the camera's own shutter, auto-edits and uploads it.

Both save originals to Files › On My iPhone › CamLink (and optionally Photos), and can auto-edit JPEGs and upload to
FTP/FTPS and Google Drive, with the same upload gallery, subfolders, upload-original-too and delete-after-upload options.

## Layout
| Path | What | Android counterpart |
| --- | --- | --- |
| `CamLinkCore/` | Swift package: auto-edit, PTP containers, Sony property parsing, labels. `swift test` runs golden-image tests. | `edit/AutoEditCore.kt`, `ptp/*`, `Exposure.kt` |
| `CamLink/Camera/` | ImageCaptureCore transport (`requestSendPTPCommand`), Sony PC Remote protocol, the USB loop | `PtpUsb.kt`, `SonyCamera.kt`, `CameraViewModel.kt`, `lite/TetherService.kt` |
| `CamLink/Upload/` | Settings, FTP/FTPS client, Google Drive (OAuth + REST), upload queue + history | `upload/*.kt` |
| `CamLink/UI/` | SwiftUI screens | `ui/*.kt` |
| `project.yml` | XcodeGen project spec | |

## USB on iOS
iOS has no raw USB access. The camera is reached through Apple's ImageCaptureCore: `ICDeviceBrowser` finds it, the app asks
for "control cameras" permission, ImageCaptureCore opens the PTP session, and every Sony vendor operation (0x9201 handshake
phases 1–3, 0x9202, 0x9209, 0x9205, 0x9207, GetObject 0xFFFFC002 live view / 0xFFFFC001 captured image, pending count from
0xD215) is passed through with `requestSendPTPCommand`. GetThumb (0x100A) is never sent. Because ImageCaptureCore owns the
session, the app never sends OpenSession/CloseSession itself. Settings › Debug log shows every step.

## Build
CI (`.github/workflows/ios.yml`, macOS runner) runs the core tests, generates the project with XcodeGen and builds an
unsigned IPA (`CODE_SIGNING_ALLOWED=NO`, `Payload/` zipped). Pushing a tag `ios-v*` attaches the IPA to a GitHub release.

On a Mac: `brew install xcodegen && cd ios && xcodegen generate && open CamLink.xcodeproj`.

### Google Drive
Drive sign-in uses the **iOS OAuth client** "CamLink iOS" (bundle ID `app.camlink.usb`) in Google Cloud project
`module-roy`. Its client ID and reversed-ID URL scheme are build settings in `project.yml` (`GOOGLE_IOS_CLIENT_ID`,
`GOOGLE_IOS_URL_SCHEME`) and end up in Info.plist. Client IDs aren't secret; no client secret is used (PKCE).
