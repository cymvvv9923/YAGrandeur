# Virtual Console(차 상태 조작 화면)을 연다. 브라우저 창이 열려 있어야 값 바꾸기(PUT)가 된다.
. "$PSScriptRoot\env.ps1"
& $ADB forward tcp:8088 tcp:8088 | Out-Null
Start-Process 'http://localhost:8088'
