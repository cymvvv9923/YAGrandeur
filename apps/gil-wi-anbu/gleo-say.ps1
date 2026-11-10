# 시연용: 운전자가 Gleo에게 말한 것처럼 앱의 음성 기능을 부른다 (주행 중에는 화면 없이 말로만).
# Gleo가 승인 후 보내는 것과 같은 신호(assets/schema.json의 기능 이름 + ACTION_PARAM JSON)를 보낸다.
#   .\gleo-say.ps1 save              # "여기 저장해 줘"
#   .\gleo-say.ps1 save '커피 맛집'   # "여기 커피 맛집이라고 저장해 줘"
#   .\gleo-say.ps1 undo              # "방금 저장한 거 취소해 줘"
#   .\gleo-say.ps1 count             # "오늘 몇 개 저장했어?"
#   .\gleo-say.ps1 leave '조심히 와' -Group 가족             # "다온, 가족한테 조심히 와 남겨 줘" (지금 위치에, 가족 모임 모두)
#   .\gleo-say.ps1 leave '비 오니 천천히' -Group 친구 -When rain # 비 오는 날 열리는 쪽지 (when 키: place rain night together door start park long region warning)
#   .\gleo-say.ps1 leave '여기 맛있어' -Group 가족 -Spot     # 방금 저장한 곳에 남기기 (보낸 뒤 저장한 곳은 지움)
# 받는 쪽은 그룹(가족·친구·연인 또는 모임 이름)으로 말한다. 사람 이름을 말하면 Gleo가 전화·문자 담당으로 넘기기 때문.
# leave의 첫 인자(내용)는 -Memo 자리에 쓴다. -Group을 빼면 지금 관리하는 모임. -To(사람 이름)는 시험용으로만 남겨 둠.
param(
    [ValidateSet('save', 'undo', 'count', 'leave')][string]$What = 'save',
    [string]$Memo = '',
    [string]$Group = '',  # 가족 · 친구 · 연인 · 모임 이름
    [string[]]$To = @(),   # (시험용) 사람 이름, 쉼표로 여럿
    [string]$When = '',
    [switch]$Spot,
    [ValidateSet('broadcast', 'service')][string]$Via = 'broadcast'
)
$envPs1 = "$PSScriptRoot\..\_common\scripts\env.ps1"
if (Test-Path $envPs1) { . $envPs1 }
if (-not $ADB -or -not (Test-Path $ADB)) { $ADB = (Get-Command adb -ErrorAction SilentlyContinue).Source }
if (-not $ADB) { 'adb를 찾지 못했어요.'; exit 1 }
$pkg = 'ai.gilwi.anbu'
$action = @{ save = 'SAVE_SPOT'; undo = 'UNDO_SPOT'; count = 'COUNT_SPOTS'; leave = 'LEAVE_DAON' }[$What]
# 한글이 adb 셸을 지나며 깨지지 않게 \uXXXX로 바꿔 보낸다 (JSON은 그대로 읽는다)
function Esc([string]$s) { ($s.ToCharArray() | ForEach-Object { '\u{0:x4}' -f [int]$_ }) -join '' }
if ($What -eq 'leave') {
    if (-not $Memo) { '남길 내용을 써 주세요. 예: .\gleo-say.ps1 leave "조심히 와" -Group 가족'; exit 1 }
    $parts = @('"text":"' + (Esc $Memo) + '"')
    if ($Group) { $parts += '"group":"' + (Esc $Group) + '"' }
    if ($To) { $parts += '"to":"' + (Esc ($To -join ',')) + '"' }
    if ($When) { $parts += '"when":"' + $When + '"' }
    if ($Spot) { $parts += '"useSavedSpot":true' }
    $param = '{' + ($parts -join ',') + '}'
} else {
    $p = @(); if ($Memo) { $p += '"memo":"' + (Esc $Memo) + '"' }; if ($Group) { $p += '"group":"' + (Esc $Group) + '"' }
    $param = '{' + ($p -join ',') + '}'
}
# PowerShell 5.1은 외부 프로그램에 넘길 때 따옴표를 지우므로 \"로 남긴다
# 문서·실측 모두 component "service"는 방송(sendBroadcast)으로 온다. 기본은 방송, -Via service 로 서비스 길도 시험할 수 있다
if ($Via -eq 'broadcast') {
    & $ADB shell am broadcast --user current -n "$pkg/.GleoReceiver" -a $action --es ACTION_PARAM ("'" + $param.Replace('"', '\"') + "'") | Out-Null
} else {
    & $ADB shell am start-foreground-service --user current -n "$pkg/.GleoActionService" -a $action --es ACTION_PARAM ("'" + $param.Replace('"', '\"') + "'") | Out-Null
}
"Gleo($Via) → $action $(if ($Memo) { "($Memo)" })"
