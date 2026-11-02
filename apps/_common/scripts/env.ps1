# 모든 앱이 같이 쓰는 개발 환경 경로. 다른 스크립트가 . "$PSScriptRoot\env.ps1" 로 불러 쓴다.
$env:JAVA_HOME        = 'D:\Android\jdk\jdk-17.0.20.1+1'
$env:ANDROID_HOME     = 'D:\Android\Sdk'
$env:ANDROID_AVD_HOME = 'D:\Android\avd'
$env:GRADLE_USER_HOME = 'D:\Android\.gradle'
$Global:ADB    = 'D:\Android\Sdk\platform-tools\adb.exe'
$Global:GRADLE = 'D:\Android\gradle\gradle-8.10.2\bin\gradle.bat'
$Global:AVD    = 'Pleos_Connect_v2_1'
$Global:APPS   = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
