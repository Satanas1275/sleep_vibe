$adb = "$PSScriptRoot\tools\android-sdk\platform-tools\adb.exe"
& $adb devices
& $adb install -r "$PSScriptRoot\Sommeil.apk"
if ($LASTEXITCODE -eq 0) {
    & $adb shell monkey -p com.paul.sleeptrack -c android.intent.category.LAUNCHER 1 | Out-Null
}
