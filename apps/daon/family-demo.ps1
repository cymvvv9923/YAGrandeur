# 시연 스크립트(demo.ps1, scenario-hyeonggyu.ps1)가 같이 쓰는 함수.
# 서버는 열쇠가 있어야 받으므로, 폰 대신 시연용 가족(우리집, 초대 코드 demo-home)에 들어가 열쇠를 받아 쓴다.
# . "$PSScriptRoot\family-demo.ps1" 로 불러 쓴다. $ADB와 $pkg가 먼저 정해져 있어야 한다.

$Server = 'http://localhost:8090'

# 폰 열쇠 받기 (초대 링크를 열고 동의한 것과 같다)
function Get-PhoneKey {
    $r = Invoke-RestMethod -Method Post "$Server/api/join" -ContentType 'application/json' -Body (@{ inviteCode = 'demo-home'; agreed = $true } | ConvertTo-Json)
    $r.key
}

# 폰 웹에서 메시지를 남기는 것과 같다
function Post-Message($key, $body) {
    Invoke-RestMethod -Method Post "$Server/api/circles/me/messages" -Headers @{ 'X-Key' = $key } -ContentType 'application/json; charset=utf-8' `
        -Body ([Text.Encoding]::UTF8.GetBytes(($body | ConvertTo-Json -Compress))) | Out-Null
}

# 모임의 차 중 그 사람 차 (PowerShell 5는 빈 목록([])도 한 덩어리로 돌려주므로 ownerId로 골라낸다)
function Get-CarOf($key, $ownerId) {
    Invoke-RestMethod "$Server/api/circles/me/cars" -Headers @{ 'X-Key' = $key } | ForEach-Object { $_ } |
        Where-Object { $_.ownerId -eq $ownerId } | Select-Object -First 1
}

# 에뮬레이터 차가 초대 코드(demo-home)로 우리집에 들어가 $ownerId의 차가 된다 (차 화면에서 「초대 코드로 들어가기」를 한 것과 같다).
# 이미 그 사람 차로 들어가 있으면 아무것도 하지 않는다.
function Connect-EmulatorCar($key, $carName = '아버지 그랜저', $ownerId = 'dad') {
    if (Get-CarOf $key $ownerId) { '   (차는 이미 우리집에 들어가 있어요)'; return }
    & $ADB shell am start --user current -n "$pkg/.MainActivity" --es invite demo-home --es member $ownerId | Out-Null
    $car = $null
    foreach ($i in 1..30) { $car = Get-CarOf $key $ownerId; if ($car) { break }; Start-Sleep 1 }
    if (-not $car) { '   차가 모임에 들어가지 못했어요. 차 화면을 확인해 주세요'; return }
    Invoke-RestMethod -Method Post "$Server/api/devices/$($car.id)/rename" -Headers @{ 'X-Key' = $key } -ContentType 'application/json; charset=utf-8' `
        -Body ([Text.Encoding]::UTF8.GetBytes((@{ name = $carName } | ConvertTo-Json -Compress))) | Out-Null
    "   차가 초대 코드 demo-home 으로 우리집에 들어가 「$carName」이 됐어요"
    Start-Sleep 3
}
