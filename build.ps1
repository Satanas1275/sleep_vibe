$root = $PSScriptRoot
$env:JAVA_HOME = "$root\tools\jdk-21.0.12.1+1"
$env:GRADLE_USER_HOME = "$root\tools\gradle-home"
$env:ANDROID_HOME = "$root\tools\android-sdk"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
Push-Location $root
& "$root\tools\gradle-8.14.3\bin\gradle.bat" --no-daemon assembleDebug
if ($LASTEXITCODE -eq 0) {
    Copy-Item "$root\app\build\outputs\apk\debug\app-debug.apk" "$root\Sommeil.apk" -Force
    Write-Host "APK : $root\Sommeil.apk"
}
Pop-Location
