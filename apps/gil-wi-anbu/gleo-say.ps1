# 시연용: 운전자가 Gleo에게 말한 것처럼 앱의 음성 기능을 부른다 (주행 중에는 화면 없이 말로만).
# Gleo가 승인 후 보내는 것과 같은 신호(assets/schema.json의 기능 이름 + ACTION_PARAM JSON)를 보낸다.
#   .\gleo-say.ps1 save              # "여기 저장해 줘"
#   .\gleo-say.ps1 save '커피 맛집'   # "여기 커피 맛집이라고 저장해 줘"
#   .\gleo-say.ps1 undo              # "방금 저장한 거 취소해 줘"
#   .\gleo-say.ps1 count             # "오늘 몇 개 저장했어?"
param(
    [ValidateSet('save', 'undo', 'count')][string]$What = 'save',
    [string]$Memo = ''
)
$envPs1 = "$PSScriptRoot\..\_common\scripts\env.ps1"
if (Test-Path $envPs1) { . $envPs1 }
if (-not $ADB -or -not (Test-Path $ADB)) { $ADB = (Get-Command adb -ErrorAction SilentlyContinue).Source }
if (-not $ADB) { 'adb를 찾지 못했어요.'; exit 1 }
$pkg = 'ai.gilwi.anbu'
$action = @{ save = 'SAVE_SPOT'; undo = 'UNDO_SPOT'; count = 'COUNT_SPOTS' }[$What]
# 한글이 adb 셸을 지나며 깨지지 않게 \uXXXX로 바꿔 보낸다 (JSON은 그대로 읽는다)
$param = if ($Memo) { '{"memo":"' + (($Memo.ToCharArray() | ForEach-Object { '\u{0:x4}' -f [int]$_ }) -join '') + '"}' } else { '{}' }
# PowerShell 5.1은 외부 프로그램에 넘길 때 따옴표를 지우므로 \"로 남긴다
& $ADB shell am broadcast --user current -n "$pkg/.GleoReceiver" -a $action --es ACTION_PARAM ("'" + $param.Replace('"', '\"') + "'") | Out-Null
"Gleo → $action $(if ($Memo) { "($Memo)" })"
