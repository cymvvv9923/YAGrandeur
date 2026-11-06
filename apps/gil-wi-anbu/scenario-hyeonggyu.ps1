# 가상 시나리오: 형규의 비 오는 퇴근길 (docs\시나리오_형규의_퇴근길.md)
#   강남역에서 형규가 탐(운전자 지정 → 문 → 어머니 「차에 탈 때」) → 출발 · 비(아버지) · 양재 사거리(미성) · 밤길(원우)
#   → 양재시민의숲에서 정차 → 메시지 3개 재생 → 홈의 오늘의 길 · 서클 소식
# 준비는 demo.ps1과 같다: 에뮬레이터, 서버(node server\server.js), 콘솔 포트(_common\scripts\console.ps1).
param(
    [switch]$Fresh,        # 서버 데이터와 앱 기록을 처음 상태로 하고 시나리오 메시지를 새로 남긴다
    [string]$Shots = ''    # 장면마다 화면을 캡처해 이 폴더에 저장
)
# adb 찾기: 개발 PC 설정(env.ps1) → ANDROID_HOME → 기본 SDK 위치 → PATH
$envPs1 = "$PSScriptRoot\..\_common\scripts\env.ps1"
if (Test-Path $envPs1) { . $envPs1 }
if (-not $ADB -or -not (Test-Path $ADB)) {
    $ADB = @("$env:ANDROID_HOME\platform-tools\adb.exe", "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe") |
        Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1
    if (-not $ADB) { $ADB = (Get-Command adb -ErrorAction SilentlyContinue).Source }
    if (-not $ADB) { 'adb를 찾지 못했어요. Android SDK platform-tools를 PATH에 넣어 주세요.'; exit 1 }
}
# 태블릿 등 다른 기기가 같이 꽂혀 있으면 adb가 고르지 못하니 에뮬레이터를 지정한다
if (-not $env:ANDROID_SERIAL) {
    $emu = & $ADB devices | Select-String '^(emulator-\d+)\s+device' | Select-Object -First 1
    if ($emu) { $env:ANDROID_SERIAL = $emu.Matches[0].Groups[1].Value }
}
$pkg = 'ai.gilwi.anbu'
function Car($path) {
    # 콘솔은 브라우저 하트비트가 끊기면 emulator_inactive를 돌려준다. 브라우저 대신 스크립트가 보낸다.
    try { Invoke-RestMethod -Method Post "http://localhost:8088/browser/session?client=demo-ps1" | Out-Null } catch {}
    Invoke-RestMethod -Method Put "http://localhost:8088$path" | Out-Null
}
function Speed($kph) { Car "/vehicle/controls?speed=$kph&accelerator=$([int]($kph / 2))&brake=0&steering=0" }
function Loc($lat, $lon) { & $ADB shell am broadcast --user current -n "$pkg/.DemoReceiver" --ef lat $lat --ef lon $lon | Out-Null }
function Shot($name) {
    if (-not $Shots) { return }
    New-Item -ItemType Directory -Force $Shots | Out-Null
    & $ADB shell screencap -p /sdcard/demo.png
    & $ADB pull /sdcard/demo.png (Join-Path $Shots "$name.png") | Out-Null
}
function Km($a, $b) {  # 두 지점 사이 거리(km)
    $r = [math]::PI / 180
    $x = ($b[1] - $a[1]) * $r * [math]::Cos(($a[0] + $b[0]) / 2 * $r)
    $y = ($b[0] - $a[0]) * $r
    6371 * [math]::Sqrt($x * $x + $y * $y)
}
. "$PSScriptRoot\family-demo.ps1"
function Post($body) { Post-Message $phoneKey $body }

# 강남역 → 강남대로 남쪽 → 양재 사거리(미성 메시지) → 양재시민의숲 (약 3.3km, demo.ps1과 같은 길)
$route = @(
    @(37.4979, 127.0276), @(37.4950, 127.0290), @(37.4920, 127.0305), @(37.4890, 127.0320),
    @(37.4865, 127.0335), @(37.4854, 127.0364), @(37.4830, 127.0362), @(37.4800, 127.0360),
    @(37.4770, 127.0357), @(37.4740, 127.0355), @(37.4707, 127.0352)
)

& $ADB forward tcp:8088 tcp:8088 | Out-Null
# 서버와 콘솔이 켜져 있는지 먼저 본다
try { Invoke-WebRequest http://localhost:8090 -UseBasicParsing -TimeoutSec 3 | Out-Null } catch { '서버가 꺼져 있어요. 2_start_server.bat을 먼저 켜 주세요.'; exit 1 }
try { Invoke-RestMethod http://localhost:8088/vehicle/gear -TimeoutSec 3 | Out-Null } catch { 'Virtual Console(8088)에 연결하지 못했어요. 에뮬레이터가 다 켜졌는지 확인해 주세요.'; exit 1 }
# 차를 처음 상태로: 정차, 와이퍼·전조등 끔, 조수석 비움, 문 닫힘
Speed 0; Car '/vehicle/gear?gear=Park'
Car '/vehicle/body?front_wiper=Off'; Car '/vehicle/lights?feature=headlamp_mode&value=Off'
Car '/vehicle/seat?kind=occupied&area=passenger&value=false'; Car '/vehicle/body?door_fl=false'
if ($Fresh) {
    Invoke-RestMethod -Method Post 'http://localhost:8090/api/dev/reset?seed=hyeonggyu' | Out-Null
    $phoneKey = Get-PhoneKey
    # 가족들이 낮에 폰 웹에서 남겨 둔 메시지 (미성의 양재 사거리 메시지는 시드에 있다)
    Post @{ authorId = 'mom';    when = 'door';  kind = 'text'; text = '형규야, 오늘 고생 많았어. 조심히 와' }
    Post @{ authorId = 'dad';    when = 'rain';  kind = 'text'; text = '비 온다. 앞차랑 거리 넉넉히 두고 와라' }
    Post @{ authorId = 'wonwoo'; when = 'night'; kind = 'text'; text = '형, 밤길이니까 졸리면 쉬었다 와' }
    # 차량 앱은 사용자 10(현재 사용자)으로 돈다
    & $ADB shell pm clear --user current $pkg | Out-Null
    & $ADB shell pm grant --user current $pkg android.permission.POST_NOTIFICATIONS
    & $ADB shell pm grant --user current $pkg android.permission.RECORD_AUDIO
}
# 주행거리는 줄일 수 없어서 지금 값에서 더한다. 0은 앱이 "값 없음"으로 보므로 0이 아닌 값에서 시작한다.
$odo = [double](Invoke-RestMethod http://localhost:8088/vehicle/odometer).traveled_distance_km
if ($odo -lt 1) { $odo = 12345; Car "/vehicle/odometer?traveled_distance_km=$odo" }

'1) 강남역에 세워 둔 형규 차에 형규가 탐 (폰에서 이 차를 「형규의 차」로 등록)'
& $ADB shell am start --user current -n "$pkg/.MainActivity" | Out-Null
if (-not $phoneKey) { $phoneKey = Get-PhoneKey }
Connect-EmulatorCar $phoneKey '형규 그랜저' 'hyeonggyu'
Start-Sleep 3
Loc $route[0][0] $route[0][1]; Start-Sleep 2
Shot '1_home_before'
# 차 주인은 등록할 때 정해진다. 혹시 늦게 반영될 때를 대비해 한 번 더 맞춘다 (Engine.setDriver)
& $ADB shell am start --user current -n "$pkg/.MainActivity" --es driver hyeonggyu | Out-Null
Start-Sleep 4
Shot '2_home_hyeonggyu'

'2) 운전석 문을 열고 앉음 → 🚪 어머니 「차에 탈 때」 메시지'
Car '/vehicle/body?door_fl=true'; Start-Sleep 4
Shot '3_door_mom'
Car '/vehicle/body?door_fl=false'; Start-Sleep 12   # 끝까지 들으면 자동 「들었어요」

'3) 출발 (기어 D) → 내비 화면으로'
Car '/vehicle/gear?gear=Drive'; Start-Sleep 1; Speed 40; Start-Sleep 3
Shot '4_drive'

'4) 강남대로를 따라 남쪽으로 · 비(와이퍼) · 양재 사거리 · 밤길(전조등)'
for ($i = 1; $i -lt $route.Count; $i++) {
    $odo += Km $route[$i - 1] $route[$i]
    Speed (40 + 10 * ($i % 3))
    Car ("/vehicle/odometer?traveled_distance_km={0:F3}" -f $odo)
    Loc $route[$i][0] $route[$i][1]
    "   {0,2}/{1}  누적 {2:F1}km" -f $i, ($route.Count - 1), $odo
    if ($i -eq 2) { Car '/vehicle/body?front_wiper=Low'; '   비가 옴(와이퍼) → 🌧 아버지 메시지 대기'; Start-Sleep 1; Shot '5_rain' }
    if ($i -eq 4) { '   양재 사거리 근처 → 📍 미성 메시지 대기'; Start-Sleep 1; Shot '6_yangjae' }
    if ($i -eq 6) { Car '/vehicle/lights?feature=headlamp_mode&value=LowBeam'; '   전조등 켬 → 🌙 원우 메시지 대기' }
    Start-Sleep 2
}

'5) 양재시민의숲에 정차 (기어 P) → 모인 메시지 3개 재생'
Speed 0; Start-Sleep 1; Car '/vehicle/gear?gear=Park'; Start-Sleep 5
Shot '7_play'

'6) 다 들으면 홈: 오늘의 길 · 서클 소식에 반영'
Start-Sleep 45
& $ADB shell am start --user current -n "$pkg/.MainActivity" | Out-Null
Start-Sleep 3
Shot '8_home_after'
'끝'
