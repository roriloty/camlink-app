# Runs the Kotlin auto-edit on D:\camlink-autoedit\verify\*_small.png / *_full.png (see autoedit verify.py)
Set-Location (Join-Path $PSScriptRoot "..")
.\gradlew.bat testReleaseUnitTest --tests app.camlink.usb.edit.AutoEditCoreTest -i --console=plain *>&1 |
    Select-String -Pattern "plan .* ms|FAILED|PASSED|SKIPPED|^e: |BUILD (SUCCESSFUL|FAILED)"
