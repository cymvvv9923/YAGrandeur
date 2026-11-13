# apps

Pleos Connect 에뮬레이터에서 돌아가는 다온의 소스와 공통 스크립트입니다.

| 폴더 | 내용 |
| :-- | :-- |
| [`daon/`](daon/README.md) | 다온: 차량 앱(Kotlin) · 그룹 서버(Node.js) · 폰 웹 |
| `_common/scripts/` | 모든 빌드가 같이 쓰는 스크립트 |

## 공통 스크립트 (PowerShell)

| 스크립트 | 하는 일 |
| :-- | :-- |
| `env.ps1` | JDK · Android SDK · Gradle 경로 |
| `emulator.ps1` | Pleos Connect 에뮬레이터 켜기 |
| `console.ps1` | Virtual Console 열기 (차 상태 바꾸기) |
| `build-install.ps1` | 빌드 + 설치 (`-App daon [-Run]`) |

```powershell
.\_common\scripts\emulator.ps1
.\_common\scripts\console.ps1                     # 브라우저 창을 열어 둬야 차 상태를 바꿀 수 있음
.\_common\scripts\build-install.ps1 -App daon -Run
```

- 서버 포트는 8090입니다. 8088은 Virtual Console이 씁니다.
