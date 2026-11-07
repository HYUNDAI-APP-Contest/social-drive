# PLEOS Connect 런타임 검증 — 2026-11-07

이 문서는 PLEOS Connect v2.1.0 AVD(Android 14, user 10)에서 직접 관찰한 범위만 기록한다. 실차 검증이나 AWS 운영 E2E 증거로 확대 해석하지 않는다.

## 확인된 실제 SDK 경로

| 기능 | 관찰 결과 | 개인정보·안전 경계 |
|---|---|---|
| Vehicle | Vehicle Service 연결 후 gear callback `GEAR_PARK` 수신. 앱에 `PLEOS 연결됨 · 차량 상태` 표시 | 읽기 전용 `CAR_DRIVE`; 차량 제어 권한 없음 |
| NaviHelper 3.0.5 | 공개 데모 목적지 버튼에서 `requestRoute` 호출, `onRouteStarted` callback 수신, PLEOS Navigation 화면의 추천 경로 3개와 `안내 시작` 확인 | 주차 상태에서만 요청. 개인 경로 좌표가 아닌 명시적 공개 데모 목적지만 전달 |
| FusedLocation 1.1.3 | 런타임 권한 승인 후 에뮬레이터 GPS 입력을 수신해 녹화 종료 시 정규화 경로와 총 거리 저장 | 앱 동의 + 권한 + 활성 녹화가 모두 있을 때만 callback 등록. Room에는 위·경도가 아닌 0~1 경로점과 집계 `distanceMeters`만 저장 |
| TextToSpeech 3.3.1 | 기록 시작 피드백에서 `TtsCallback onReady/onStart` 관찰 | 온디바이스 모드, 빈 문구 거부 |
| SpeechToText 3.3.1 | 게시 화면에서 `request`와 `ReadyToStartRecognition`, 취소 시 `stop/close` 관찰 | 주차 상태에서만 시작. 최종·비공백 결과만 초안에 반영 |

TTS/STT 초기화 콜백이 누락될 때 설정이 무기한정 `확인 중`에 머물지 않도록 10초 상한을 적용했다. STT 요청이 종료·오류·최종 결과 콜백 없이 멈추는 경우에도 30초 뒤 세션을 종료하고 `stop()`을 호출하도록 보강했다. 세션 번호로 이전 타이머가 새 인식을 종료하지 못하게 했고, 앱 해제 후 늦은 초기화 콜백도 무시한다.

보강 APK를 데이터 유지 설치한 뒤 실제 게시 화면의 제목 음성 입력을 다시 실행했다. 화면에 `음성을 듣고 있어요…`가 표시됐고 SDK `request` 뒤 `ReadyToStartRecognition`을 수신했다. `음성 입력 취소`를 누르면 SDK `stop`이 호출되고 `음성 입력을 취소했어요.`로 복귀했으며 앱 프로세스 크래시는 없었다.

## 2026-11-08 최신 APK 재검증

커밋 `4444efe`를 포함한 PLEOS debug APK(`SHA-256 77f8adc03b3de24ebf1eee6df3fd93949fc5c70d81bd8ef92079e8ce4e74b611`)를 기존 데이터를 유지한 채 같은 AVD에 업데이트 설치했다. 장치는 API 34, `RELEASE.release_connect_v2.1.0 test-keys`, 물리 해상도 `2560x1440`이었고 콜드 스타트는 751 ms에 완료됐다. 앱 PID가 유지됐으며 시작 직후 FATAL, ANR, 앱 대상 `SecurityException`은 없었다.

설정 화면에서 차량 상태, 차량 내비게이션, 위치 연동, 음성 안내, 음성 인식 다섯 항목이 모두 `PLEOS 연결됨`으로 표시됐다. 저장된 위치 동의는 `현재 수집하지 않음 · 기존 동의 저장됨`으로 복원됐고 새 기록을 시작하자 `실시간 위치 연결됨`, `경로 수집 중`으로 전환됐다. 이번 짧은 기록에는 정규화 가능한 위치 표본이 2개 미만이어서 저장 후 데모 아트워크로 안전하게 대체됐다. 이는 SDK 연결 표시만으로 실시간 경로가 저장됐다고 주장하지 않는 fail-closed 동작이다.

게시 화면에서 제목 음성 입력을 시작하자 앱은 `음성을 듣고 있어요…`를 표시했고 PLEOS 온디바이스 STT가 `ko_KR` 세션, `ReadyToStartRecognition`, 16 kHz `AudioRecord` 시작을 기록했다. 취소 후 SDK `stop`과 `close`, 녹음 세션 해제까지 관찰됐고 화면은 `음성 입력을 취소했어요.`로 복귀했다. 기록 시작·완료의 TTS 호출도 예외 없이 통과했지만 공급자 서비스가 발화 완료 로그나 청취 가능한 출력을 제공하지 않는 한계는 기존과 같다.

### 제출 보안 보강 이후 재확인

커밋 `32b5ab4`까지 포함한 PLEOS debug APK(`SHA-256 80502c1c97120fa4b71f3d1bbf29e8fc3917811c3cf59b1e9d5f6b1ca3f8466d`)를 기존 데이터를 유지한 채 다시 설치하고 user 10에서 실행했다. 콜드 스타트는 687 ms에 완료됐고 앱은 홈 화면에 `PLEOS 연결됨 · 차량 상태`와 기존 실시간 경로 기록을 표시했다. 실행 직후 FATAL, ANR, 앱 대상 `SecurityException`은 관찰되지 않았다.

활성 사용자인 user 10의 패키지 권한을 다시 조회한 결과 `CAR_DRIVE`, `NAVI_ROUTE_SEARCH`, `FUSED_LOCATION`, `TTS_SERVICE`, `STT_SERVICE`가 모두 허용돼 있었다. 이 가운데 `FUSED_LOCATION`은 `granted=true, USER_SET`인 위험 권한이고 나머지 네 권한은 설치 시 허용되는 일반 권한이다. 비활성 user 0에는 위치 권한이 거부돼 있지만 실행 중인 user 10의 권한·SDK 연결 상태에는 영향을 주지 않는다.

같은 프로세스의 설정 화면에서 다음 실제 문구를 확인했다.

| 항목 | 설정 화면 표시 |
|---|---|
| 차량 상태 | `PLEOS 연결됨 · 차량 상태` |
| 차량 내비게이션 | `PLEOS 연결됨 · 차량 내비게이션` |
| 위치 연동 | `PLEOS 연결됨 · 위치 연동`, `실시간 경로: 사용 가능` |
| 음성 안내 | `PLEOS 연결됨 · 음성 안내` |
| 음성 인식 | `PLEOS 연결됨 · 음성 인식` |
| AWS 클라우드 | `AWS · 빌드 설정 필요` |

따라서 이전에 보고된 두 SDK 미허용 상태는 현재 활성 사용자에서는 재현되지 않는다. 반면 AWS 표시는 의도한 fail-closed 상태이며, 실제 계정의 public Cognito/API 식별자를 주입하고 승인된 인프라를 배포하기 전에는 클라우드 연결 완료로 간주하지 않는다.

## NaviHelper 재현 절차

1. 최신 `app-pleos-debug.apk`를 설치하고 `com.socialdrive.app/.MainActivity`를 user 10에서 실행한다.
2. 홈의 `빛나는 해안도로의 저녁` 카드를 연다.
3. 주차 상태에서 `차량 내비게이션으로 보내기`를 누른다.
4. 앱 프로세스 로그에서 다음 순서를 확인한다.

```text
NaviHelper addListener
NaviHelper requestRoute
NaviHelper onRouteStarted
```

5. PLEOS Navigation 화면에 목적지 `공개 데모 목적지`, 추천 경로, `안내 시작`이 표시되는지 확인한다.

PLEOS AVD는 이 흐름에서 `onRoutingCompleted` 없이 `onRouteStarted`를 보냈다. 앱은 `onRouteStarted`를 안내 인계 성공의 종결 증거로 처리하고 요청 타임아웃을 취소한다. 15초를 지난 뒤 앱으로 돌아와도 `차량 내비게이션에서 경로를 시작했어요.`가 유지되고 잘못된 시간 초과가 표시되지 않는 것을 재검증했다.

상세 화면에서 system Back을 입력했을 때 앱의 홈 화면으로 복귀하고 `MainActivity`가 focused/resumed 상태를 유지하는 것도 AVD 멀티윈도우에서 확인했다.

## 위치 저장 검증

에뮬레이터에 서로 다른 GPS 좌표를 입력하고 녹화를 저장한 뒤 user 10의 Room DB/WAL을 함께 읽었다. `normalized_route_points`에는 0~1 범위의 `horizontal`, `vertical`, 연속 `sequence`, `UNPUBLISHED`만 존재했다. 원본 `latitude`/`longitude` 열은 스키마에 없다. 녹화 종료 후 설정 화면은 `현재 수집하지 않음 · 기존 동의 저장됨`으로 돌아왔다.

커밋 `ccbea23` 이후 같은 AVD에서 데이터 유지 방식으로 APK를 업데이트하고 녹화를 시작한 뒤 `adb emu geo fix`로 서로 다른 네 좌표를 순차 입력했다. 녹화 중 접근성 트리는 `실시간 경로 수집 중인 배경 · 하이라이트 0개`를 표시해, 아직 정규화하지 않은 데모 경로선을 실제 경로처럼 노출하지 않았다. 종료·초안 저장 뒤 홈 카드에는 SDK가 수신한 초기 표본을 포함한 `5개 경로점 · 기기에 보존`과 `실시간 경로`가 표시됐다. 이 검증 중 앱 `FATAL EXCEPTION`과 앱 대상 `SecurityException`은 관찰되지 않았다.

Room v7 거리 집계 구현 뒤에는 오래된 초기 위치와 현재 위치 사이 약 21 km 점프를 실제 거리로 오인하는 현상을 AVD에서 재현했다. 연속 표본 사이 5 km 초과 점프를 구간 경계로 처리하고 가장 긴 최신 연속 구간만 정규화·집계하도록 수정했다. 수정 APK에서 오래된 좌표 1개 뒤 현재 좌표 3개를 입력한 재검증 결과 새 홈 카드는 `88 m · 6초`, `실시간 경로`를 표시했고 FATAL·SecurityException은 없었다. 수신 타이밍상 현재 좌표 두 개가 집계된 결과이며, 오래된 좌표까지 포함했던 기존 `21.4 km` 오집계는 새 기록에서 재발하지 않았다.

같은 종단 흐름에서 초안 저장 직후 Room과 Compose 상태가 한 프레임 어긋나 `memory story identity mismatch`로 종료되는 크래시도 재현했다. 다른 기록에 임시 상태를 투영하지 않고 해당 프레임의 원본 기록을 그대로 표시하도록 수정한 뒤, 크래시 당시 생성된 3개 경로점 초안을 데이터 유지 설치로 다시 열어 정상 복구됨을 확인했다.

Room v8에서는 위치 수집 동의에 `consentPolicyVersion`을 추가했다. 기존 AVD의 버전 없는 `Granted` 동의를 데이터 유지 APK 설치로 마이그레이션한 결과, 설정 화면이 `현재 수집하지 않음 · 동의도 저장하지 않음`으로 전환되고 수집을 복원하지 않았다. `실시간 경로 켜기`를 누르면 Android 권한 처리보다 먼저 수집 시점, PLEOS SDK 처리, 원본 위·경도 미저장·미업로드, 정규화 경로·거리의 기기 보관, 철회·삭제 방법과 정책 `2026-11-07.v1`을 한 화면에 고지했다. `동의하고 권한 확인`을 명시적으로 누른 뒤에만 `기존 동의 저장됨`으로 복귀했고, 크래시는 없었다.

현재 정책 동의를 유지한 채 user 10의 `FUSED_LOCATION` 권한만 철회한 재검증에서 앱은 `실시간 경로: 권한이 거부됨`, `현재 수집하지 않음 · 기존 동의 저장됨`, `위치 권한 다시 확인`을 표시했다. 재허용 후 `사용 가능`으로 복귀했고 `dumpsys package`의 user 10 권한도 `granted=true, USER_SET`으로 확인했다. 권한 철회·SDK 미지원·위치 콜백 오류 모두에서 등록된 콜백을 해제하고 메모리의 원본 표본을 지우도록 보강했다.

PLEOS AVD의 시스템 권한 창은 커스텀 권한을 `알 수 없는 작업`으로 표시했다. 앱은 그 창보다 먼저 구체적인 위치 고지를 표시하지만 시스템 문구는 앱이 정의하는 표준 권한 라벨이 아니다. 상용 승인 전 PLEOS 공급자에게 production 이미지의 `pleos.car.permission.FUSED_LOCATION` 사용자 표시 문구가 명확한지 확인해야 한다.

## 플랫폼 위치 로그 개인정보 게이트

FusedLocation 1.1.3 콜백을 재검증하는 동안 앱 프로세스가 아닌 별도 PLEOS 위치 서비스 프로세스가 디버그 logcat에 `LocationData` 전체를 출력하는 것을 관찰했다. Social Drive는 이 값을 로그에 쓰지 않고 정규화 전 메모리에서만 처리하지만, 앱 R8 규칙으로 다른 프로세스의 로그를 제거할 수는 없다. 공식 가이드의 공개 API는 지원 확인·초기화·위치 조회/콜백·해제·차단 안내로 구성되며 로깅 제어 API는 문서화돼 있지 않다. 현재 사용 중인 1.1.3이 최신 릴리즈임도 릴리즈 노트와 일치한다.

- [FusedLocation 1.1.3 API 개요](https://document.pleos.ai/api-reference/connect-sdk-pleos/FusedLocation/intro)
- [PLEOS SDK 릴리즈 노트](https://document.pleos.ai/docs/pleos-only/release-note/sdk-release-note)

따라서 상용 승인 전 PLEOS 공급자에게 production 이미지에서 원본 위치 로그가 비활성화되거나 앱/제3자가 접근할 수 없음을 확인해야 한다. 확인 전에는 “원본 좌표를 앱이 저장·업로드·기록하지 않는다”까지만 주장하며 “플랫폼 전체에서 로그가 없다”고 주장하지 않는다. release 빌드는 별도로 앱 프로세스의 `android.util.Log` 호출을 R8에서 제거한다.

## Cognito 앱 부팅 경계 검증

실제 AWS 계정을 변경하지 않고 앱 연결 경계까지 검증하기 위해 형식에 맞는 가짜 API host·User Pool ID·public mobile Client ID를 빌드 인자로만 주입했다. 이 APK를 데이터 유지 설치한 후 Amplify Cognito 플러그인이 크래시 없이 초기화됐고, 저장된 세션이 없는 정상 첫 실행에서 설정 화면이 `AWS · 로그인 필요`와 Cognito 로그인 폼을 표시했다. 이 검증에서 로그인을 제출하지 않아 인증·API 네트워크 요청은 발생하지 않았다.

검증 후에는 클라우드 인자 없이 APK를 다시 빌드·설치했다. 생성된 `BuildConfig` 값이 빈 문자열임을 확인했고, user 10의 동의·초안 데이터를 유지한 채 설정 화면이 `AWS · 빌드 설정 필요`로 복귀됨을 확인했다. 가짜 식별자는 현재 APK와 생성 산출물에 남아 있지 않다.

이 검증은 SRP 클라이언트 초기화·미로그인 UX·fail-closed 설정까지만 증명한다. 실제 서명된 Cognito JWT, API Gateway authorizer, private S3 업로드는 아래의 미검증 범위로 유지한다.

## TTS/STT 권한과 재연결 검증

PLEOS 음성 권한을 Manifest 선언에만 의존하지 않도록 `TTS_SERVICE`와 `STT_SERVICE`를 각각 초기화 전에 확인하고, 거부 시 SDK 호출을 차단하는 fail-closed 경계를 추가했다. 설정 화면은 둘 중 하나라도 거부되면 `음성 권한 다시 확인`을 제공하며, 앱 복귀 시 권한을 다시 읽는다. 재허용 뒤에는 아직 초기화되지 않은 서비스만 초기화해 STT 리스너 중복 등록을 피한다.

새 PLEOS debug APK를 데이터 유지 설치한 뒤 user 10에서 재실행했다. `dumpsys package`는 `pleos.car.permission.TTS_SERVICE`와 `pleos.car.permission.STT_SERVICE`가 모두 `granted=true`임을 보였고, 설정 화면은 각각 `PLEOS 연결됨 · 음성 안내`, `PLEOS 연결됨 · 음성 인식`을 표시했다. 앱 PID의 error log에는 앱 크래시나 SDK 권한 오류가 없었으며 AVD OpenGL swap 경고 1건만 있었다. 이는 AVD의 권한·초기화·상태 표시는 증명하지만 실제 차량의 마이크 인식률과 오디오 출력 품질은 증명하지 않는다.

같은 앱 프로세스에서 기기 초안의 게시 화면으로 이동해 `음성으로 제목 입력`을 실행했다. 화면이 즉시 `음성을 듣고 있어요…`와 `음성 입력 취소`로 전환됐고, AVD에 음성을 주입하지 않은 상태에서 30초 제한 뒤 `인식된 음성이 없어요. 다시 시도해 주세요.`로 복귀했다. 다시 시작한 세션에서 취소를 누르면 1초 안에 `음성 입력을 취소했어요.`로 바뀌었다. 두 흐름 모두 앱이 응답 상태를 유지했고 error log에 STT 또는 앱 예외가 없었다. 이 증거는 실제 SDK 요청·timeout·stop 콜백과 사용자 복구 UX를 포함하지만 음성 텍스트 정확도는 포함하지 않는다.

TTS는 동일 AVD에서 `PLEOS 연결됨 · 음성 안내` 상태로 초기화된 뒤 기록 시작과 종료 완료 동작을 실행해 앱의 `speak()` 호출 경로를 통과했다. 기록 화면은 `실시간 경로 수집 중`으로 전환됐고 종료 확인 후 초안 저장과 게시 화면 이동까지 완료됐으며 앱 PID에 TTS 예외나 크래시는 없었다. 다만 공급자 TTS 서비스는 발화 요청·완료를 logcat이나 앱 callback으로 노출하지 않았고 이 환경에서는 실제 차량 스피커 출력을 청취할 수 없다. 따라서 AVD 증거는 권한·초기화·호출 무예외까지만 인정하며, 음성 출력 자체와 음량·중첩·오디오 포커스는 실차 수용 시험 항목으로 유지한다.

## Vehicle 주차 기어 콜백 검증

데이터가 보존된 PLEOS Connect Emulator의 user 10에서 테스트 APK만 추가 설치하고 `PleosGearRuntimeSmokeTest`를 `requirePleosGear=true`로 실행했다. 실제 Vehicle SDK가 전달한 `GEAR_PARK`가 애플리케이션 소유 motion source의 `MotionState.Parked`까지 제한 시간 안에 도달했으며, 계측 결과는 `OK (1 test)`, 1.443초였다. 테스트는 원시 기어 값을 로그에 남기지 않는다.

검증 뒤 테스트 APK `com.socialdrive.app.test`만 제거하고 대상 앱을 다시 실행했다. 기존 초안과 실시간 경로 데이터가 유지됐고 설정 화면의 `PLEOS 연결됨 · 차량 상태`도 정상 복귀했다. 이 결과는 AVD의 PARK 콜백 경로만 증명한다. D/R/N 전환, 서비스 장애 전환, 실제 차량 신호 품질은 아직 증명하지 않았다.

## 아직 증명하지 않은 범위

- 실제 차량에서의 GNSS 품질, 장시간 주행, 오디오 입력·출력 품질
- Vehicle D/R/N 및 서비스 장애 콜백과 실제 차량의 기어 신호
- 실제 AWS 계정의 Cognito 로그인, API Gateway authorizer, S3 업로드 E2E
- PLEOS production 이미지의 위치 서비스 원본 좌표 로그 비활성화 또는 접근 통제 확인
- 제출용 승인 인증서로 서명한 release APK
- 물리 PLEOS 장치의 system Back 및 멀티윈도우 수용 시험(AVD system Back은 통과)

AWS 배포·IAM 변경·운영 데이터 변경과 실차 검증은 별도 승인 및 외부 환경이 필요하다.
