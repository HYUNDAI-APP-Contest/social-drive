# 실제 환경 점검 인계

최종 갱신: 2026-11-08 (Asia/Seoul)

이 문서는 PLEOS 에뮬레이터에서 직접 확인한 상태와 외부 승인이 필요한 미검증 항목을 구분한다. AWS 자격 증명, 사용자 토큰, presigned URL과 원본 위치는 기록하지 않는다.

## 확인된 실행 스냅샷

아래 결과는 앱 코드 커밋 `9988261`에서 만든 디버그 APK를 관측한 스냅샷이다. 이후 커밋의 최신 APK 여부는 파일 해시를 다시 계산해 판단하며, 이 문서의 과거 해시를 최신 산출물 식별자로 사용하지 않는다.

- 대상: `emulator-5554`, Android user 10, PLEOS 가로 화면 `2560×1440`
- 패키지: `com.socialdrive.app`
- APK: `android/app/build/outputs/apk/pleos/debug/app-pleos-debug.apk`
- SHA-256: `62a49e096549f57595fe632c91c1ac77a714117f0066223f7b3612c71cea2e3e`
- 설치 결과: `adb install -r` 성공
- 시작 결과: `com.socialdrive.app/.MainActivity`가 현재 포커스이며 시작 직후 FATAL/ANR 없음
- 설정 화면: 차량 상태, 차량 내비게이션, 위치 연동, 음성 안내, 음성 인식이 모두 `PLEOS 연결됨`
- 위치 권한: user 10에서 `pleos.car.permission.FUSED_LOCATION` 허용
- 설치 권한: `TTS_SERVICE`, `STT_SERVICE`, `CAR_DRIVE`, `NAVI_ROUTE_SEARCH`와 SDK 바인딩 권한 허용
- AWS 상태: 공개 빌드 출력값을 주입하지 않은 APK이므로 `AWS · 빌드 설정 필요`로 안전하게 닫힘
- 회귀 검증: 핵심 Kotlin 105개, PLEOS/Demo 전체 단위 테스트와 PLEOS lint 통과
- 게시 복구 계측: Cognito 토큰 시간 초과 뒤 CREATE `retry` → CREATE/UPLOAD/PUBLISH 완료를 별도 disposable AVD에서 `OK (1 test)`로 확인

설정 화면의 `PLEOS 연결됨`은 SDK 초기화 콜백과 권한 상태를 확인한 결과다. 실차의 물리적인 D/R/N 변속, 실제 주행 위치 변화, 마이크 음성 품질과 스피커 출력 품질까지 검증했다는 의미는 아니다.

키보드와 STT로 입력한 제목·설명은 UTF-16 문자 수가 아니라 Unicode 코드포인트 기준으로 제한하고 저장소에서도 같은 제한을 다시 적용한다. 경계의 supplementary 문자를 반쪽 surrogate로 저장하지 않는 것은 핵심 단위 테스트로 확인했으며, 실제 한국어 STT 결과 품질을 검증했다는 의미는 아니다.

키보드 연속 입력과 STT 최종 결과는 한 개의 순차 저장 경로를 사용한다. 같은 초안에서 저장 대기 중인 중간 값은 최신 값으로 합치고, 서로 다른 초안의 대기 값은 ID별로 각각 보존해 느린 이전 Room 쓰기가 최신 편집을 덮어쓰지 않게 했다. 지연 저장을 재현한 양쪽 flavor 단위 테스트로 순서와 ID별 보존을 확인했다.

개별 Room 쓰기가 예외를 내더라도 순차 저장 worker 자체는 종료하지 않아 이후 편집을 계속 저장한다. 정상적인 코루틴 취소는 삼키지 않는다. 실패 뒤 다음 최신 편집이 처리되는 것은 양쪽 flavor 단위 테스트로 확인했으며, 디스크 용량 부족 같은 지속 장애를 성공으로 표시하거나 무한 재시도하지 않는다.

게시 대기열에 넣기 전에는 순차 저장 경로가 유휴 상태가 될 때까지 기다린다. 마지막 키 입력이나 STT 결과가 아직 Room에 반영 중인 동안 게시 스냅샷을 만들지 않으며, 저장이 끝난 뒤에만 `PublishScheduler.enqueue`를 호출한다. 지연 쓰기 중 장벽이 반환하지 않는 동작을 양쪽 flavor 단위 테스트로 확인했다.

FusedLocation 콜백은 좌표 유효성뿐 아니라 SDK가 양수 타임스탬프를 제공하는 경우 직전 표본보다 최신인지 검사한다. 같은 시각의 중복 표본과 역순 표본은 원시 메모리 버퍼에 넣지 않으며, 새 녹화·종료·권한 철회·연결 해제 시 시간 기준도 초기화한다. SDK가 타임스탬프를 제공하지 않는 `0` 이하 값은 기존 PLEOS 호환성을 위해 허용하고 거리 계산 단계의 중복·비현실 이동 필터를 적용한다. 이는 단위 테스트와 AVD 설치·기동으로 검증했으며 실제 차량 GNSS 정확도 증거는 아니다.

활성 녹화 중 FusedLocation 연결 또는 수신 오류가 발생하면 이미 받은 원시 경로를 지우지 않고 현재 리스너를 해제한 뒤 2초 후 재연결한다. 재시도 시점에 권한·동의·녹화 상태가 모두 유지되고 새 리스너가 없을 때만 다시 등록하며, 권한이 사라지면 기존 개인정보 정책대로 버퍼를 삭제한다. 재연결 상태 게이트는 단위 테스트로 확인했지만 SDK가 오류 강제 주입 API를 제공하지 않아 실제 서비스 오류 후 callback 복귀는 실차 미검증 범위다.

Cognito 갱신의 시간 초과, Amplify `ServiceException`/`UnknownException`, 내부 `IOException`은 로그인 세션을 지우지 않는 일시 장애로 분류한다. 명시적 로그아웃, 세션 만료, 권한 거부와 API 401만 재인증 경로로 전환한다. 이 구분은 로컬 SDK 타입·Worker 계측으로 검증했으며 실제 AWS 계정 통신 증거는 아니다.

API와 presigned object 요청은 리디렉션·암묵적 연결 재시도를 끄고 2 MiB 응답 상한과 30초 전체 호출 제한을 적용한 동일 `OkHttpClient` 연결 풀을 프로세스 동안 재사용한다. 반복 작업마다 새 소켓 풀을 만들지 않는 것은 양쪽 flavor 단위 테스트로 확인했지만, 실제 API Gateway/S3 연결 재사용 관찰은 AWS E2E 범위다.

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

로컬 구성 게이트는 2026-11-08에 다음 두 방향을 확인했다.

- 속성 미주입: 공개 출력 여섯 개를 모두 누락 항목으로 표시하고 빌드 실패
- 비밀값 없는 정상 형식 예시: HTTPS API, 허용 업로드 호스트, 승인 동의 정책 버전, 일치하는 리전/User Pool ID, 26자 public client ID로 검증 성공

형식 예시의 성공은 해당 호스트나 Cognito 리소스가 실제 존재하거나 배포됐다는 뜻이 아니다.

## 아직 미검증

- 승인된 AWS 계정과 실제 Cognito/API/S3의 종단 간 통신
- 승인된 릴리스 인증서로 서명한 제출 APK
- 실제 차량의 D/R/N 전환과 주행 중 안전 제한
- 실제 차량 GNSS 경로의 장시간 정확도와 SDK 재연결
- 실제 마이크·스피커 환경의 STT/TTS 품질

이 항목들이 확인되기 전에는 AWS 실연결, 실차 검증 또는 제출용 릴리스 완료로 표시하지 않는다.
