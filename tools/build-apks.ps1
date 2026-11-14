# Builds the release APK and copies it to ..\CamLinkUSB-APK
Set-Location (Join-Path $PSScriptRoot "..")
$version = "1.13.0"
.\gradlew.bat assembleRelease --console=plain *>&1 |
    Select-String -Pattern "^e: |error:|BUILD (SUCCESSFUL|FAILED)|What went wrong" -Context 0,3
$out = Join-Path $PSScriptRoot "..\..\CamLinkUSB-APK"
Copy-Item app\build\outputs\apk\release\app-release.apk (Join-Path $out "CamLink-$version.apk") -Force
"copied CamLink-$version.apk"
