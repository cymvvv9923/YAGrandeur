# 공모전 앱 모음

Pleos Connect 에뮬레이터에서 돌아가는 앱을 **앱마다 폴더 하나씩** 관리합니다.

## 앱 목록

| 폴더 | 앱 | 기획 문서 | 상태 |
| :-- | :-- | :-- | :-- |
| [gil-wi-anbu](gil-wi-anbu/README.md) | 길 위의 안부 | `../원우_최종.md` | MVP 동작 확인 (2026-10-29) |

## 폴더 규칙

```
apps/
├─ README.md            ← 이 파일 (앱 목록)
├─ _common/scripts/     ← 모든 앱이 같이 쓰는 스크립트
│   ├─ env.ps1            JDK·SDK·Gradle 경로
│   ├─ emulator.ps1       에뮬레이터 켜기
│   ├─ build-install.ps1  빌드 + 설치  (-App <폴더이름> [-Run])
│   └─ console.ps1        Virtual Console 열기
└─ <앱 폴더>/            ← 영문 소문자-하이픈 이름
    ├─ README.md          무엇을 하는 앱인지, 실행 방법
    ├─ car-app/           차량 앱 (Android 프로젝트, Gradle)
    ├─ server/            (필요할 때) 서버
    ├─ web/               (필요할 때) 폰 웹
    └─ demo.ps1           (선택) 시연 시나리오
```

- 앱끼리 코드를 섞지 않습니다. 앱마다 `applicationId`를 다르게 합니다 (예: `ai.gilwi.anbu`).
- 서버가 있는 앱은 포트를 다르게 씁니다. 사용 중: **8090** (길 위의 안부). 8088은 Virtual Console이라 쓰지 않습니다.

## 새 앱 추가하기

1. `gil-wi-anbu/car-app`을 새 폴더로 복사합니다 (`app/build` 와 `.gradle` 폴더는 빼고).
2. `settings.gradle.kts`의 `rootProject.name`, `app/build.gradle.kts`의 `namespace`·`applicationId`를 바꿉니다.
3. `app/src/main/java/` 아래 패키지 폴더 이름과 `package` 줄을 바꿉니다.
4. 위 앱 목록 표에 한 줄 추가합니다.

## 공통 실행 순서 (PowerShell)

```powershell
.\_common\scripts\emulator.ps1
.\_common\scripts\console.ps1                          # 브라우저 창은 열어 둬야 차 상태를 바꿀 수 있음
.\_common\scripts\build-install.ps1 -App gil-wi-anbu -Run
```

## 에뮬레이터에서 알게 된 것 (모든 앱 공통)

- 차량 앱은 **사용자 10**으로 돕니다. 앱 데이터 지우기는 `adb shell pm clear --user current <패키지>`.
- Virtual Console 값 바꾸기: `PUT http://localhost:8088/vehicle/gear?gear=Park|Drive|Reverse|Neutral`, `PUT /vehicle/odometer?traveled_distance_km=<값>` (주행거리는 줄일 수 없음). 콘솔 브라우저 창이 닫히거나 오래 백그라운드에 있으면 `emulator_inactive` 오류가 납니다.
- NaviHelper `getCurrentLocationInfo()`는 **경로 안내 없이도** 위치를 줍니다 (기본 37.4127, 127.0947).
- `adb emu geo fix`로는 내비 위치가 **바뀌지 않습니다**. 위치 이동 시연은 앱에 시연용 입력을 따로 만들어야 합니다.
- TTS: Pleos 음성 엔진(`ai.pleos.playground.caas`)이 있지만 일반 앱에서 초기화하면 실패(status -1)합니다. 사용 신청 방법을 확인해야 합니다.
- 한글이 들어간 경로에서도 `android.overridePathCheck=true`를 넣으면 빌드됩니다.
