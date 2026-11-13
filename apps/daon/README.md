# 다온 (DAON) 소스

가족 · 친구 · 연인 그룹이 장소나 운전 순간에 남긴 짧은 목소리나 글을, 다른 구성원이 그 장소를 지나거나 그 순간을 만났을 때 자기 차에서 들려주는 Pleos Connect 차량 앱입니다.

설치와 시연 방법은 저장소 맨 위 [`README.md`](../../README.md)에 있습니다.

## 구성

| 폴더 · 파일 | 내용 |
| :-- | :-- |
| `car-app/` | 차량 앱 (Kotlin, Android 프로젝트). 패키지 `ai.daon.car` |
| `server/` | 그룹 서버 (Node.js, 외부 패키지 없음). 그룹 · 구성원 · 메시지를 보관하고 차와 폰을 이어 줌 |
| `web/` | 폰 웹. 차에 타지 않은 가족도 폰에서 지도로 장소를 골라 목소리나 글을 남김 |
| `docs/` | 개발 중 검증 기록과 화면 |
| `gleo-say.ps1` | 에뮬레이터에서 Gleo가 보내는 것과 같은 신호를 보내 음성 명령을 시험 |
| `demo.ps1` · `family-demo.ps1` · `scenario-hyeonggyu.ps1` | 서버 버전 시연 스크립트 |

## 두 가지 빌드

| | 앱만 (공모전 제출판) | 서버 연결 |
| :-- | :-- | :-- |
| 설정 | `STANDALONE = true` (기본) | `STANDALONE = false`, `SERVER_URL` |
| 서버 | 앱 안의 `LocalServer.kt`가 서버 API를 그대로 처리 | `server/server.js` (`node server.js`, 포트 8090) |
| 데이터 | 시연용 그룹 · 메시지 · 목소리(`assets/demo`)로 시작 | 서버의 `data/db.json` |

설정은 `car-app/app/build.gradle.kts`의 `buildConfigField`에 있습니다.

## 차량 앱 코드 (`car-app/app/src/main/java/ai/daon/car`)

| 파일 | 하는 일 |
| :-- | :-- |
| `CarSignals.kt` | Vehicle SDK · NaviHelper · GPS를 묶어 기어, 주행거리, 위치, 와이퍼, 하향등, 조수석 탑승, 벨트, 운전석 문, 경고등, VIN을 하나의 Listener로 전달 |
| `ConditionRules.kt` | 신호를 그대로 쓰지 않고 오작동을 거르는 기준 (와이퍼 15초, 하향등 10초 + 일몰, 도착 P 20초 등) |
| `Engine.kt` | 앱의 두뇌: 조건 판단, 근처 메시지 계산, 대기열, 재생 흐름, 「들었어요」 자동 전송 |
| `Player.kt` | 목소리는 MediaPlayer, 글은 Pleos TTS(안 되면 안드로이드 TTS) |
| `DaonService.kt` | 포그라운드 서비스(specialUse · microphone · location). 내비가 앞에 있어도 위치 수신과 녹음을 유지, 부팅 때 자동 시작 |
| `Notifier.kt` | 주행 중 도착 알림, 정차 중 재생 알림(「들었어요」 · 「다시 듣기」 · 「나중에」) |
| `GleoReceiver.kt` | Gleo 기능 `RECORD_DAON`(「녹음해 줘」)을 받아 7초 안에 응답 |
| `VoiceNote.kt` · `Recorder.kt` | “우리집 녹음해 줘” → 안내 → 신호음 → 녹음 → 1.8초 무음이면 끝 → 결과 안내 |
| `MainActivity.kt` · `GlassMap.kt` | 정차 중 화면: 메시지 지도, 남기기, 소식, 그룹 관리, 시연, 설정 |
| `Regions.kt` | 시 · 도 17개, 시 · 군 · 구 251개 경계(`assets/geo`)로 「그 지역에 들어가면」과 지도 집계 |
| `TripStore.kt` | 오늘 주행 기록, 설정, 그룹 연결 열쇠를 기기 안에만 저장 |
| `Api.kt` · `LocalServer.kt` | 서버 API 호출. 앱만 빌드에서는 LocalServer가 같은 API를 처리 |
| `Dictation.kt` | 정차 중 말로 글 쓰기 (안드로이드 SpeechRecognizer) |
| `ProbeReceiver.kt` | 개발용: 플랫폼 기능이 차에서 되는지 하나씩 시험 (debug 빌드만) |

## 운전 상태에 따른 동작

| 상태 | 화면 | 다온이 하는 일 |
| :-- | :-- | :-- |
| 정차(P) | 다온 화면 | 메시지 남기기 · 지도 · 그룹 관리. 기다리던 메시지 재생 |
| 주행(D) | 기본 내비가 앞에 | 다온은 뒤에서 소리 · 알림 · Gleo로만 동작. 조작 화면은 닫힘 |
| 후진(R) | 후방 카메라 | 내비를 띄우지 않음 |

- 차의 위치와 상태는 서버로 보내지 않고, 조건 판단은 모두 차 안에서 합니다.
- 보낸 사람에게 가는 것은 「들었어요」와 답장뿐입니다.
- 조수석에 누가 타면 보낸 사람을 가리고 들을지 먼저 묻습니다.

## Gleo 연동 (`assets/schema.json`)

| 기능 | 파라미터 | 동작 |
| :-- | :-- | :-- |
| `RECORD_DAON` | `group` (선택) | 시작 신호만 받고, 내용은 앱이 직접 녹음. 그룹 이름을 말하지 않으면 지금 그룹에 남김 |

- Gleo는 `ai.daon.car.RECORD_DAON` 브로드캐스트(또는 `GleoActionService`)로 부르고, 앱은 `APP_ACTION_RESPONSE`로 7초 안에 답합니다.
- 응답은 `mode: silent`로 보내고 한국어 안내는 앱이 TTS로 직접 읽습니다.
- 에뮬레이터 시험: `.\gleo-say.ps1` 또는

  ```powershell
  adb shell am broadcast --user current -n ai.daon.car/.GleoReceiver -a RECORD_DAON --es ACTION_PARAM '{}'
  ```

## 빌드 · 실행

```powershell
cd apps
.\_common\scripts\emulator.ps1                     # 에뮬레이터 켜기
.\_common\scripts\build-install.ps1 -App daon -Run  # 빌드 + 설치 + 실행
```

서버 버전으로 돌릴 때는 `STANDALONE`을 `false`로 바꾸고 `node daon\server\server.js`를 먼저 켭니다. 에뮬레이터 안에서 PC는 `10.0.2.2`로 보입니다.
