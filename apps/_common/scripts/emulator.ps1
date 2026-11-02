# Pleos 에뮬레이터를 켜고 부팅이 끝날 때까지 기다린다.
. "$PSScriptRoot\env.ps1"
$running = & $ADB devices | Select-String 'emulator-\d+\s+device'
if (-not $running) {
    Start-Process -FilePath "$env:ANDROID_HOME\emulator\emulator.exe" -ArgumentList '-avd', $AVD, '-no-snapshot-save' -WindowStyle Minimized
}
& $ADB wait-for-device
for ($i = 0; $i -lt 60; $i++) {
    if ((& $ADB shell getprop sys.boot_completed) -match '1') { 'emulator ready'; exit 0 }
    Start-Sleep 3
}
'emulator boot timeout'; exit 1
