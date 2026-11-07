# 실제 환경 점검 인계

최종 갱신: 2026-11-08 (Asia/Seoul)

이 문서는 PLEOS 에뮬레이터에서 직접 확인한 상태와 외부 승인이 필요한 미검증 항목을 구분한다. AWS 자격 증명, 사용자 토큰, presigned URL과 원본 위치는 기록하지 않는다.

## 확인된 실행 스냅샷

아래 결과는 앱 코드 커밋 `298db32`에서 만든 디버그 APK를 관측한 스냅샷이다. 이후 커밋의 최신 APK 여부는 파일 해시를 다시 계산해 판단하며, 이 문서의 과거 해시를 최신 산출물 식별자로 사용하지 않는다.

- 대상: `emulator-5554`, Android user 10, PLEOS 가로 화면 `2560×1440`
- 패키지: `com.socialdrive.app`
- APK: `android/app/build/outputs/apk/pleos/debug/app-pleos-debug.apk`
- SHA-256: `aa5f00e66ecf7a0175d084628d4427ddf5c852f79db7abd6c5016329f9a9ccd6`
- 설치 결과: `adb install -r` 성공
- 시작 결과: `com.socialdrive.app/.MainActivity`가 현재 포커스이며 시작 직후 FATAL/ANR 없음
- 설정 화면: 차량 상태, 차량 내비게이션, 위치 연동, 음성 안내, 음성 인식이 모두 `PLEOS 연결됨`
- 위치 권한: user 10에서 `pleos.car.permission.FUSED_LOCATION` 허용
- 설치 권한: `TTS_SERVICE`, `STT_SERVICE`, `CAR_DRIVE`, `NAVI_ROUTE_SEARCH`와 SDK 바인딩 권한 허용
- AWS 상태: 공개 빌드 출력값을 주입하지 않은 APK이므로 `AWS · 빌드 설정 필요`로 안전하게 닫힘

설정 화면의 `PLEOS 연결됨`은 SDK 초기화 콜백과 권한 상태를 확인한 결과다. 실차의 물리적인 D/R/N 변속, 실제 주행 위치 변화, 마이크 음성 품질과 스피커 출력 품질까지 검증했다는 의미는 아니다.

## 재현 명령

다음 명령은 값을 출력하지 않는 상태 확인용이다.

```bash
adb -s emulator-5554 shell dumpsys window | rg -m1 'mCurrentFocus|mFocusedApp'
adb -s emulator-5554 shell dumpsys package com.socialdrive.app
adb -s emulator-5554 logcat -d -t 800 | rg -i 'FATAL EXCEPTION|ANR in|Process: com.socialdrive.app'
shasum -a 256 android/app/build/outputs/apk/pleos/debug/app-pleos-debug.apk
```

권한 확인 시 Android user 0과 현재 PLEOS user 10의 결과를 혼동하지 않는다. `dumpsys package`의 `User 10` runtime permissions와 공통 install permissions를 확인한다.

## AWS 승인 후 점검

1. 승인된 CloudFormation의 공개 출력 여섯 개만 저장소 밖 Gradle 속성으로 전달한다.
2. 같은 속성으로 `verifyPleosCloudConfiguration`을 통과시킨다.
3. Cognito 테스트 사용자로 로그인해 `AWS · 로그인 검증됨`을 확인한다.
4. 초안 생성, COVER/ROUTE 업로드, 게시 확인, 공개 피드·상세·커버·경로 조회, 삭제 204 확인을 순서대로 실행한다.
5. 로그아웃 뒤 인증 요청이 네트워크 전 안전하게 중단되는지 확인한다.

구체적인 출력 매핑과 금지 항목은 `docs/aws-app-connection.md`를 따른다. 앱 작업자는 AWS 배포, IAM 변경, 테스트 사용자 생성 또는 운영 데이터 변경을 수행하지 않는다.

## 아직 미검증

- 승인된 AWS 계정과 실제 Cognito/API/S3의 종단 간 통신
- 승인된 릴리스 인증서로 서명한 제출 APK
- 실제 차량의 D/R/N 전환과 주행 중 안전 제한
- 실제 차량 GNSS 경로의 장시간 정확도와 SDK 재연결
- 실제 마이크·스피커 환경의 STT/TTS 품질

이 항목들이 확인되기 전에는 AWS 실연결, 실차 검증 또는 제출용 릴리스 완료로 표시하지 않는다.
