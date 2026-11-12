# Builds the full and lite release APKs and copies them to ..\CamLinkUSB-APK
Set-Location (Join-Path $PSScriptRoot "..")
$version = "1.11.0"
.\gradlew.bat assembleFullRelease assembleLiteRelease --console=plain *>&1 |
    Select-String -Pattern "^e: |error:|BUILD (SUCCESSFUL|FAILED)|What went wrong" -Context 0,3
$out = Join-Path $PSScriptRoot "..\..\CamLinkUSB-APK"
Copy-Item app\build\outputs\apk\full\release\app-full-release.apk (Join-Path $out "CamLinkUSB-$version.apk") -Force
Copy-Item app\build\outputs\apk\lite\release\app-lite-release.apk (Join-Path $out "CamLinkLite-$version.apk") -Force
"copied CamLinkUSB-$version.apk and CamLinkLite-$version.apk"
