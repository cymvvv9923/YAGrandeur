# 사용법: .\build-install.ps1 -App gil-wi-anbu [-Run]
# apps\<App>\car-app 을 빌드해서 에뮬레이터에 설치한다. -Run 이면 실행까지.
param([Parameter(Mandatory)][string]$App, [switch]$Run)
. "$PSScriptRoot\env.ps1"
$proj = Join-Path $APPS "$App\car-app"
if (-not (Test-Path $proj)) { "앱 폴더가 없어요: $proj"; exit 1 }
Push-Location $proj
& $GRADLE --no-daemon -q :app:assembleDebug
$code = $LASTEXITCODE
Pop-Location
if ($code -ne 0) { 'build failed'; exit $code }
$apk = Join-Path $proj 'app\build\outputs\apk\debug\app-debug.apk'
& $ADB install -r -g $apk
if ($Run) {
    $appId = (Select-String -Path (Join-Path $proj 'app\build.gradle.kts') -Pattern 'applicationId\s*=\s*"([^"]+)"').Matches[0].Groups[1].Value
    & $ADB shell monkey -p $appId -c android.intent.category.LAUNCHER 1 | Out-Null
    "started $appId"
}
