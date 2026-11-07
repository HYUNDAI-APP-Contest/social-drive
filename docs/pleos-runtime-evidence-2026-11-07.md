# PLEOS Connect 런타임 검증 — 2026-11-07

이 문서는 PLEOS Connect v2.1.0 AVD(Android 14, user 10)에서 직접 관찰한 범위만 기록한다. 실차 검증이나 AWS 운영 E2E 증거로 확대 해석하지 않는다.

## 확인된 실제 SDK 경로

| 기능 | 관찰 결과 | 개인정보·안전 경계 |
|---|---|---|
| Vehicle | Vehicle Service 연결 후 gear callback `GEAR_PARK` 수신. 앱에 `PLEOS 연결됨 · 차량 상태` 표시 | 읽기 전용 `CAR_DRIVE`; 차량 제어 권한 없음 |
| NaviHelper 3.0.5 | 공개 데모 목적지 버튼에서 `requestRoute` 호출, `onRouteStarted` callback 수신, PLEOS Navigation 화면의 추천 경로 3개와 `안내 시작` 확인 | 주차 상태에서만 요청. 개인 경로 좌표가 아닌 명시적 공개 데모 목적지만 전달 |
| FusedLocation 1.1.3 | 런타임 권한 승인 후 에뮬레이터 GPS 입력을 수신해 녹화 종료 시 정규화 경로 저장 | 앱 동의 + 권한 + 활성 녹화가 모두 있을 때만 callback 등록. Room에는 위·경도가 아닌 0~1 `horizontal`/`vertical`만 저장 |
| TextToSpeech 3.3.1 | 기록 시작 피드백에서 `TtsCallback onReady/onStart` 관찰 | 온디바이스 모드, 빈 문구 거부 |
| SpeechToText 3.3.1 | 게시 화면에서 `request`와 `ReadyToStartRecognition`, 취소 시 `stop/close` 관찰 | 주차 상태에서만 시작. 최종·비공백 결과만 초안에 반영 |

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

## 아직 증명하지 않은 범위

- 실제 차량에서의 GNSS 품질, 장시간 주행, 오디오 입력·출력 품질
- 실제 AWS 계정의 Cognito 로그인, API Gateway authorizer, S3 업로드 E2E
- 제출용 승인 인증서로 서명한 release APK
- 물리 PLEOS 장치의 system Back 및 멀티윈도우 수용 시험(AVD system Back은 통과)

AWS 배포·IAM 변경·운영 데이터 변경과 실차 검증은 별도 승인 및 외부 환경이 필요하다.
