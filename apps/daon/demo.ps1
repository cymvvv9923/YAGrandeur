# 시연 시나리오: 아버지가 강남역에서 탐(문 → 「차에 탈 때」) → 어머니가 옆자리에(「옆자리에 누가 탄 날」)
#   → 출발 · 비(와이퍼) · 양재 사거리(미성 메시지) 통과 · 밤길(전조등) → 양재시민의숲에서 정차 → 메시지 재생 → 일기
# 준비: 에뮬레이터 켜기(_common\scripts\emulator.ps1), 서버 켜기(node server\server.js),
#       콘솔 포트 연결(_common\scripts\console.ps1). 콘솔 브라우저는 없어도 된다(스크립트가 하트비트를 보낸다).
# 에뮬레이터 차는 실제로 움직이지 않는다. 그래서 기어·속도·누적 주행거리는 콘솔로 바꾸고,
# 위치는 DemoReceiver로 경로를 따라 보낸다(앱 화면을 띄우지 않아 내비가 그대로 앞에 있다).
param(
    [switch]$Fresh,        # 서버 데이터와 앱 기록을 처음 상태로
    [string]$Shots = ''    # 단계마다 화면을 캡처해 이 폴더에 저장
)
. "$PSScriptRoot\..\_common\scripts\env.ps1"
# 태블릿 등 다른 기기가 같이 꽂혀 있으면 adb가 고르지 못하니 에뮬레이터를 지정한다
if (-not $env:ANDROID_SERIAL) {
    $emu = & $ADB devices | Select-String '^(emulator-\d+)\s+device' | Select-Object -First 1
    if ($emu) { $env:ANDROID_SERIAL = $emu.Matches[0].Groups[1].Value }
}
$pkg = 'ai.daon.car'
function Car($path) {
    # 콘솔은 브라우저 하트비트가 30초쯤 끊기면 emulator_inactive를 돌려준다. 브라우저 대신 스크립트가 보낸다.
    try { Invoke-RestMethod -Method Post "http://localhost:8088/browser/session?client=demo-ps1" | Out-Null } catch {}
    Invoke-RestMethod -Method Put "http://localhost:8088$path" | Out-Null
}
function Speed($kph) { Car "/vehicle/controls?speed=$kph&accelerator=$([int]($kph / 2))&brake=0&steering=0" }
function Loc($lat, $lon) { & $ADB shell am broadcast -n "$pkg/.DemoReceiver" --ef lat $lat --ef lon $lon | Out-Null }
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

# 강남역 → 강남대로 남쪽 → 양재 사거리(미성 메시지, 반경 400m) → 양재시민의숲 (약 3.3km)
$route = @(
    @(37.4979, 127.0276), @(37.4950, 127.0290), @(37.4920, 127.0305), @(37.4890, 127.0320),
    @(37.4865, 127.0335), @(37.4854, 127.0364), @(37.4830, 127.0362), @(37.4800, 127.0360),
    @(37.4770, 127.0357), @(37.4740, 127.0355), @(37.4707, 127.0352)
)

. "$PSScriptRoot\family-demo.ps1"
function Post($body) { Post-Message $phoneKey $body }

& $ADB forward tcp:8088 tcp:8088 | Out-Null
# 차를 처음 상태로: 정차, 와이퍼·전조등 끔, 조수석 비움, 문 닫힘
Speed 0; Car '/vehicle/gear?gear=Park'
Car '/vehicle/body?front_wiper=Off'; Car '/vehicle/lights?feature=headlamp_mode&value=Off'
Car '/vehicle/seat?kind=occupied&area=passenger&value=false'; Car '/vehicle/body?door_fl=false'
if ($Fresh) {
    Invoke-RestMethod -Method Post 'http://localhost:8090/api/dev/reset?seed=hyeonggyu' | Out-Null
    $phoneKey = Get-PhoneKey
    # 차 상태로 열리는 메시지 (장소 메시지 2개는 시드에 있다)
    Post @{ authorId = 'yongmin';  when = 'door';     kind = 'text'; text = '아버지, 오늘도 조심히 다녀오세요' }
    Post @{ authorId = 'mom';      when = 'together'; kind = 'text'; text = '옆에 누구 태웠어요? 같이 맛있는 거 드세요' }
    Post @{ authorId = 'yongmin';  when = 'rain';     kind = 'text'; text = '비 오니까 천천히 다니세요, 아버지' }
    Post @{ authorId = 'miseong';  when = 'night';    kind = 'text'; text = '아빠, 밤길 조심해서 오세요' }
    # 차량 앱은 사용자 10(현재 사용자)으로 돈다
    & $ADB shell pm clear --user current $pkg | Out-Null
    & $ADB shell pm grant --user current $pkg android.permission.POST_NOTIFICATIONS
    & $ADB shell pm grant --user current $pkg android.permission.RECORD_AUDIO
}
# 주행거리는 줄일 수 없어서 지금 값에서 더한다
$odo = [double](Invoke-RestMethod http://localhost:8088/vehicle/odometer).traveled_distance_km
# 새 에뮬레이터는 0km인데, 앱은 0을 "값 없음"으로 본다. 실제 차처럼 0이 아닌 값에서 시작한다.
if ($odo -lt 1) { $odo = 12345; Car "/vehicle/odometer?traveled_distance_km=$odo" }
& $ADB shell am start --user current -n "$pkg/.MainActivity" | Out-Null
if (-not $phoneKey) { $phoneKey = Get-PhoneKey }
Connect-EmulatorCar $phoneKey '아버지 그랜저' 'dad'
& $ADB shell am start --user current -n "$pkg/.MainActivity" --es driver dad | Out-Null
Start-Sleep 6
Loc $route[0][0] $route[0][1]; Start-Sleep 2
'1) 강남역에서 정차 중 (기어 P)'; Shot '1_parked'

'2) 운전석 문을 열고 탐 → 「차에 탈 때」 메시지 (용민)'
Car '/vehicle/body?door_fl=true'; Start-Sleep 4
Shot '2_door'
Car '/vehicle/body?door_fl=false'; Start-Sleep 12   # 끝까지 보면 자동 「들었어요」

'3) 조수석에 누가 앉음 → 「옆자리에 누가 탄 날」 메시지 (어머니)'
Car '/vehicle/seat?kind=occupied&area=passenger&value=true'; Start-Sleep 4
Shot '3_together'
Start-Sleep 12

'4) 출발 (기어 D) → 내비 화면으로'
Car '/vehicle/gear?gear=Drive'; Start-Sleep 1; Speed 40; Start-Sleep 3
Shot '4_drive'

'5) 강남대로를 따라 주행 · 비가 오고(와이퍼) 어두워짐(전조등)'
for ($i = 1; $i -lt $route.Count; $i++) {
    $odo += Km $route[$i - 1] $route[$i]
    Speed (40 + 10 * ($i % 3))
    Car ("/vehicle/odometer?traveled_distance_km={0:F3}" -f $odo)
    Loc $route[$i][0] $route[$i][1]
    "   {0,2}/{1}  누적 {2:F1}km" -f $i, ($route.Count - 1), $odo
    if ($i -eq 2) { Car '/vehicle/body?front_wiper=Low'; '   와이퍼 켬 → 「비 오는 날」 메시지 열림'; Start-Sleep 1; Shot '5_rain' }
    if ($i -eq 4) { Start-Sleep 1; Shot '6_near_message' }  # 양재 사거리 반경 안 → 도착 알림
    if ($i -eq 6) { Car '/vehicle/lights?feature=headlamp_mode&value=LowBeam'; '   전조등 켬 → 「밤길」 메시지 열림' }
    Start-Sleep 2
}

'6) 양재시민의숲에서 정차 (기어 P) → 받은 메시지 3개 재생 (양재 사거리 · 비 · 밤길)'
Speed 0; Start-Sleep 1; Car '/vehicle/gear?gear=Park'; Start-Sleep 5
Shot '7_play'

'7) 끝까지 들으면 자동 「들었어요」 → 일기에 반영'
Start-Sleep 45
Shot '8_after'
