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

## 아직 증명하지 않은 범위

- 실제 차량에서의 GNSS 품질, 장시간 주행, 오디오 입력·출력 품질
- 실제 AWS 계정의 Cognito 로그인, API Gateway authorizer, S3 업로드 E2E
- 제출용 승인 인증서로 서명한 release APK
- 물리 PLEOS 장치의 system Back 및 멀티윈도우 수용 시험(AVD system Back은 통과)

AWS 배포·IAM 변경·운영 데이터 변경과 실차 검증은 별도 승인 및 외부 환경이 필요하다.
