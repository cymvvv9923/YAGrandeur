# 다온 (DAON)

> 가족·친구·연인이 「비 오는 날」, 「밤길」, 「오래 운전할 때」, 「이 장소를 지날 때」처럼 상황을 정해 남겨 둔 메시지를, 그 상황이 되면 차가 알아서 들려주는 Pleos Connect 차량용 메시지 앱

현대자동차그룹 차량용 앱 개발 공모전(Pleos Connect) 출품작 · 팀 용아그

| 경로 | 내용 |
| :-- | :-- |
| [`apk/`](apk/) | 설치용 APK (`용아그_다온_release.apk`) |
| [`apps/daon/`](apps/daon/README.md) | 다온 소스: 차량 앱(Kotlin) · 그룹 서버(Node.js) · 폰 웹 |
| [`apps/_common/`](apps/_common/scripts/) | 에뮬레이터 실행 · 빌드 · 설치 스크립트 |
| [`아이디어_최종.md`](아이디어_최종.md) | 아이디어를 처음 정리한 문서 |
| [`아이디어/`](아이디어/) | 주제를 고르며 검토한 아이디어 문서 |

## 1. 실행 방법

**서버 없이 APK 하나만 설치하면 됩니다.** 그룹 · 메시지 · 목소리는 모두 앱 안(LocalServer)에서 처리하고, 시연용 가족 · 친구 · 연인 데이터가 들어 있습니다.

1. Pleos Connect 에뮬레이터를 켭니다.
2. APK를 설치하고 권한을 줍니다. Pleos Connect는 사용자 10에서 앱이 돕니다.

   ```powershell
   adb install -r -g apk\용아그_다온_release.apk
   adb shell pm grant --user 10 ai.daon.car android.permission.RECORD_AUDIO
   adb shell pm grant --user 10 ai.daon.car android.permission.POST_NOTIFICATIONS
   adb shell pm grant --user 10 ai.daon.car android.permission.ACCESS_FINE_LOCATION
   ```

3. 앱 목록에서 **다온**을 엽니다. 2~3초 뒤 시연용 차량(용민의 차)으로 자동 시작합니다.
4. 왼쪽 메뉴 **시연**에서 카드를 누르면 지도 위를 차가 달려 그 순간(시동, 2시간 주행, 장소, 비, 전조등 등)을 만들고 메시지가 열립니다.
   - 「메인 ① 받기 · 금요일 귀성길」: 시동 → 2시간 주행 → 천안 휴게소(지도 아래 「답장」) → 도착
   - 「메인 ② 남기기 · 토요일 강릉」: 우리 둘 녹음해 줘 → 강원 강릉시 → 정동진 전망대
   - 「질문이 나오면」: 조수석 탑승, 연인 기념일, 차에 탈 때, 비, 전조등, 경고등, 들러 가기
   - 그룹 화면에서 초대 코드 `DEMO-MISEONG`을 넣으면 미성의 차로 바뀝니다.
5. 운전 중 음성 명령: “글레오” → “다온, 우리집 녹음해 줘” → 삐 소리 뒤에 남길 말을 하면 우리집 그룹에 목소리로 남습니다.

인터넷이 없어도 시연할 수 있습니다. 지도를 크게 확대했을 때의 길 · 지명만 인터넷이 있을 때 나옵니다.

## 2. 테스트 환경

| 항목 | 내용 |
| :-- | :-- |
| 에뮬레이터 | Pleos Connect AVD (Android 14, API 34, x86_64) |
| 실차 | Pleos Connect (arm64-v8a). 한국어 TTS와 소리 재생 확인 |
| 앱 | 패키지 `ai.daon.car` · minSdk 28 · targetSdk 35 · ABI arm64-v8a, x86_64 |
| 권한 | 마이크, 알림, 위치. 차 신호는 Pleos Vehicle 권한(경고등은 `PRIVATE_CAR_SAFETY`) |
| 에뮬레이터 한계 | 기어 D를 바꿀 수 없어 주행은 「시연」 메뉴로 만듭니다. 에뮬레이터에는 TTS 엔진이 없어 글 메시지는 화면에 글로 보여 줍니다(실차는 기계음으로 읽음) |

## 3. 소스에서 빌드

JDK 17, Android SDK 35, Gradle 8.10.2가 필요합니다.

```powershell
cd apps\daon\car-app
gradle assembleRelease      # app\build\outputs\apk\release\app-release.apk
```

`apps/_common/scripts/build-install.ps1 -App daon -Run`으로 빌드부터 에뮬레이터 설치 · 실행까지 한 번에 할 수 있습니다.

## 4. 무엇을 하는 앱인가

| 기능 | 내용 |
| :-- | :-- |
| 상황 조건부 메시지 | 이 장소를 지나면 · 그 지역에 들어가면 · 도착해 세울 때 · 비 오는 날 · 밤길 · 차에 탈 때 · 시동 걸 때 · 오래 운전할 때 · 경고등이 켜질 때, 9가지 조건과 시간대 · 요일 · 기간 · 횟수 |
| 자동 음성 재생 | 조건이 맞으면 목소리는 그대로, 글은 기계음으로 들려주고, 끝까지 들으면 보낸 사람에게 「들었어요」만 전함 |
| Gleo 음성 명령 | 운전 중 “다온, (그룹 이름) 녹음해 줘”로 화면 없이 목소리를 남김 (`RECORD_DAON`) |
| 장소 답장 | 장소 메시지의 답장은 보낸 사람이 그 장소를 다시 지날 때 들림 |
| 메시지 지도 · 여러 그룹 | 전국 · 권역 지도에 못 들은 메시지가 있는 지역 표시, 가족 · 친구 · 연인 그룹을 함께 관리 |
| 개인정보 | 차의 위치와 상태는 서버로 보내지 않고 판단은 차 안에서 함. 차는 VIN 해시로만 구분 |

사용 SDK: Pleos Vehicle 2.3.2 · NaviHelper 3.0.5 · TextToSpeech 3.3.1 · SpeechToText 3.3.1 · Gleo App Integration(`assets/schema.json`) · MapLibre GL Android 11.5.2

자세한 구조와 코드 설명은 [`apps/daon/README.md`](apps/daon/README.md)에 있습니다.
