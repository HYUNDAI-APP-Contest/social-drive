# Social Drive | 소셜 드라이브

> 운전 중 좋았던 순간을 짧게 표시하고, 주차 후 드라이브 초안을 검토·공유하며, 다른 운전자의 코스를 차량 내비게이션으로 이어 달리는 것을 목표로 하는 차량용 앱입니다.

## 먼저 확인해 주세요

2026-11-02 현재 Android 앱 PR #2의 병합 커밋 `dcffaad`가 이 작업공간 HEAD의 조상이며 `android/` 소스가 통합돼 있습니다. 제출용 서명 release APK와 clean-install 증거는 승인된 signing material 및 PLEOS AVD 검증 전이므로 아직 없습니다.

- Vehicle SDK 2.3.2와 NaviHelper 3.0.5 adapter의 최초 구현 커밋은 `6475287`이며 1차 독립 검토에서 `CHANGES_REQUIRED`였습니다.
- Issue #28 NaviHelper 경계는 client AAR 3.0.5와 PLEOS Connect Emulator 2.1.0/API 34의 Navi service 3.0.4 조합에서 검증했습니다. service가 보낸 legacy `onRouteEnded`를 production adapter가 안전하게 정규화했고, exact smoke는 1/1, 실패·오류·skip 0으로 `terminal_completed`와 `released`까지 관찰했습니다. 이는 물리 차량, signed release, AWS 또는 vendor System Back 증거를 의미하지 않습니다.
- 2026-11-07 최종 hardening은 release가 이미 claim된 listener 제거를 추월하지 못하도록 session별 단일 maintenance drainer를 적용하고, terminal 관찰부터 cleanup 증거까지 하나의 20초 예산으로 제한했습니다. 전체 정적 50/50, Kotlin core 94/94, Demo unit 124/124, Pleos unit 112/112가 통과했습니다. 전용 verifier는 전체 UTP/logcat을 임시 디렉터리에서만 처리하고 source SHA·1/0/0/0 결과·앱이 기록한 callback 종류·allowlisted stage만 JSON으로 남긴 뒤 raw 경로/좌표/payload를 폐기합니다. GitHub에서 fetch한 `origin/main` `8a068c3b72cf081d59f41686fa0e15d02e398a7b`는 이 issue 브랜치의 조상입니다.
- `4f2b559116fd98ee3fe05e05fc6431b59888d0d6`은 Issue #28 이전 Android 통합 검증의 역사적 HEAD이며 현재 Issue #28 검토 HEAD가 아닙니다. 당시 PLEOS Connect Emulator 2.1.0(Android 14, user 10)에서 instrumentation 51/51, SDK-independent Kotlin 74/74, 정적 계약 29/29이 통과했습니다.
- Gradle clean, Demo/Pleos 단위 테스트, Demo release lint, Demo Debug/Release, Pleos Debug와 Pleos AndroidTest 조립이 179개 task로 성공했습니다. Demo release 산출물은 unsigned이므로 공모전 제출용 서명·설치 검증은 아직 남아 있습니다.
- Room v7에는 실제 경로 거리와 durable 409 conflict review, parked-only published-delete tombstone, bounded retry가 구현돼 있습니다. PLEOS 클라우드는 공개 Cognito/API 설정이 없으면 fail closed합니다.
- `88b4938`은 최신 앱 HEAD가 아니라 P0-3A의 재현 가능한 local bootstrap/doctor와 offline zero-network fail-fast를 남긴 **역사적 증거**입니다.
- Android에는 no-redirect OkHttp 전송과 Cognito SRP·MFA·세션 복원 어댑터가 연결돼 있습니다. 승인된 실제 스택 출력값과 테스트 사용자가 없어 AWS E2E만 아직 미검증입니다. 실제 PLEOS system Back이 embedded child task에 전달되는지는 `UNRESOLVED`입니다.
- 최신 코드에는 recording session, `highlightCount`, draft, pending queue, preset reference와 route source·consent/withdraw·로컬 `DELETE_PENDING` 상태가 포함됩니다.
- 실제 위치 권한과 개인정보 동의 기반 PLEOS 경로 수집, 거리 집계, TTS/STT 콜백이 구현됐고 PLEOS AVD에서 검증됐습니다. 원본 위경도는 Room이나 업로드 payload에 저장하지 않습니다. 실제 AWS 호출은 공개 스택 설정 부재로 미검증입니다.
- Gradle 8.9과 Android platform 35가 준비되어 실제 조립과 PLEOS AVD 계측까지 완료됐습니다. 역사적 `88b4938`의 bootstrap 차단 기록은 현재 상태가 아니라 과거 복구 증거로만 유지합니다.
- preset route와 fake motion은 실제 차량 데이터가 아닌 데모 데이터입니다.

아래 Android 명령은 통합 소스에서 실행할 수 있습니다. 제출 release는 승인된 서명 환경과 별도 clean PLEOS AVD 증거가 모두 있어야만 완료로 승격합니다.

## 3분 확인 순서

1. [현재 기능 상태](#현재-기능-상태)에서 actual/demo/future 구분을 확인합니다.
2. [실행 방법](#실행-방법)에서 현재 실행 가능 여부와 예정 명령을 확인합니다.
3. [테스트 환경과 설정](#테스트-환경과-필요한-설정)에서 Pleos AVD 요구사항을 확인합니다.
4. [APK 상태](#apk-위치와-release-상태)에서 build APK와 제출용 signed APK의 차이를 확인합니다.

## 앱 소개

### 해결하려는 문제

드라이브 코스는 SNS에서 발견하고, 장소는 지도에서 다시 찾고, 목적지와 경유지는 차량 내비게이션에 다시 입력해야 합니다. 운전 중 좋았던 순간을 자세히 기록하려는 행동은 주의 분산과도 충돌합니다.

Social Drive의 목표 흐름은 다음과 같습니다.

1. 주행 중에는 기록 상태와 `방금 좋았어` 같은 최소 입력만 제공합니다.
2. recording session, `highlightCount`, draft와 pending queue를 로컬에 보존합니다.
3. 편집과 게시 확인은 주차 상태에서만 허용합니다.
4. Parked 경계를 유지한 채 공개 코스의 목적지·경유지를 차량 Navigation 앱으로 전달합니다. 현재 증거는 production NaviHelper adapter를 직접 호출한 공개 목적지 1건의 emulator application-boundary smoke이며, 실제 UI 전체 여정이나 물리 차량 증거는 아닙니다.

### 핵심 안전 원칙

- `Driving`뿐 아니라 상태를 확인할 수 없는 `Unknown`에서도 편집·게시를 차단하는 것이 목표입니다.
- 실제 위치 기능을 추가할 때는 명시적 동의·철회와 공개 범위를 먼저 구현해야 합니다.
- 게시를 확정하기 전에는 데이터를 로컬에 보존하고, 사용자가 선택한 정보만 업로드하는 구조를 목표로 합니다.
- SDK callback이나 AVD 증거가 없는 기능을 실제 차량 연동으로 표시하지 않습니다.

## 현재 기능 상태

| 등급 | 기능 | 2026-11-02 증거 |
|---|---|---|
| `앱 구현` | Driving/Unknown 잠금, recording/draft/queue, consent/withdraw, 409 review, published delete | PR #2 병합 커밋 `dcffaad` 포함. 기존 PLEOS instrumentation 51/51, Kotlin 74/74, static 29/29 증거가 있으나 actual cloud는 미완료 |
| `앱 구현` | Pleos flavor 초기화·수명주기, Compose 안전 UI, Room/WorkManager | 통합 소스 존재. 기존 Pleos Connect Android 14 user 10 검증은 실제 Vehicle state callback이나 physical system Back 전달 증거가 아님 |
| `역사적 도구 증거` | P0-3A runtime bootstrap/doctor | `88b4938`; offline zero-network fail-fast 확인. tasks/variants/assemble 또는 앱 실행 성공 증거가 아님 |
| `앱 구현` / NaviHelper `실제 SDK` | Vehicle/NaviHelper와 cloud production 경계 | production port 통합. NaviHelper는 API 34 emulator에서 공개 목적지 1건의 initialize/request/terminal/listener removal/release를 application boundary로 확인. 실제 Vehicle callback·UI 전체 여정·actual cloud는 별도 증거 필요 |
| `데모 데이터` | preset route / fake motion | 실제 위치나 실제 Vehicle callback이 아님 |
| `미구현` | 실제 위치·경로점 수집, signed submission release APK, actual cloud E2E | release pipeline 코드는 있으나 승인 서명·clean-install 산출물이 없어 제출 release 완료 증거는 없음 |
| `실제 연동` | FusedLocation, SpeechToText, TextToSpeech | 현재 PLEOS 공개 SDK를 사용하며 권한·동의 후에만 활성화 |
| `향후 기능` | Vehicle Control | 안전 정책상 이번 MVP에서 제외 |

SDK 제공 상태는 [Pleos 프로젝트 및 SDK 상태 가이드](https://document.pleos.ai/en/docs/pleos-only/create-my-project)를 기준으로 하며, Vehicle과 NaviHelper의 구현 가능 여부와 앱에서 실제 검증됐는지는 별개입니다.

## 실행 방법

### 현재 상태

Android 소스는 현재 작업공간에 통합돼 있습니다. 제출용 APK는 승인 전 생성·게시하지 않습니다.

```bash
test -d android && echo "Android source present" || echo "Android source missing"
test -f apk/팀명_SocialDrive_release.apk && echo "Submission release APK present" || echo "Submission release APK not created"
```

예상 결과:

```text
Android source present
Submission release APK not created
```

### Android 실행 절차

일반 검증은 signing secret 없이 실행합니다.

```bash
cd android

# 데모 데이터 기반 variant
./gradlew :app:assembleDemoDebug

# Pleos SDK 연동 variant
./gradlew :app:assemblePleosDebug
```

승인된 release signing 환경과 clean worktree가 준비된 운영자는 다음 단일 entrypoint로 두 release variant와 metadata를 생성합니다. 비밀값은 저장소나 명령행에 기록하지 않습니다.

```bash
cd android
bash scripts/build-submission-release.sh
```

생성 위치는 ignored `android/build/submission/<sourceSha>/`이며, 정확히 `demoRelease`와 `pleosRelease` 두 APK 및 source SHA·variant·filename·SHA-256 metadata가 한 세트로 확정됩니다. 이후 Pleos APK만 `verify-pleos-clean-install.sh --serial <PLEOS_SERIAL> --apk <PATH>`로 wipe/factory-reset된 Pleos Connect Emulator 2.1.0/API 34에서 신규 설치·최초 실행·강제 종료·새 PID 재실행을 검증합니다. 이 결과는 Vehicle/Navi callback, physical Back, cloud/AWS 성공을 뜻하지 않습니다.

## 테스트 환경과 필요한 설정

| 항목 | 제출 목표 | 현재 검증 상태 |
|---|---|---|
| OS | macOS 개발 환경 | 로컬 호스트 사용 중 |
| JDK | JDK 17 | Gradle build에서 사용·성공 |
| Android | minSdk 28 이상, compile platform 35 | Android platform 35로 Demo/Pleos 조립 성공 |
| Gradle | 프로젝트 wrapper 8.9 | clean/unit/lint/assemble 179 tasks 성공 |
| Emulator | Pleos Connect Emulator 2.1.0, Android 14, user 10 | 앱 설치·UI 8/8·비UI 43/43 성공. physical system Back은 미검증 |
| Vehicle | Vehicle SDK 2.3.2 / Vehicle Service 대응 버전 | 초기화·release와 fail-closed Unknown 실행. PLEOS AVD 실제 PARK callback 통과, D/R/N·실차 미검증 |
| Navigation | NaviHelper 3.0.5 | PLEOS AVD에서 실제 `requestRoute`와 `onRouteStarted`, 내비게이션 추천 경로 화면 확인 |
| AWS | Cognito JWT, DynamoDB, private S3 기반 목표 | SRP/MFA/세션 복원과 no-redirect 전송 구현. 실제 스택 출력값·테스트 사용자 부재로 AWS E2E 미실행 |

P0-3A bootstrap/doctor의 과거 차단은 해소되어 build·emulator·debug APK 증거가 생겼습니다. signing secret과 keystore는 조회하지 않았고, 제출용 signed release와 actual cloud는 여전히 별도 승인·검증 경계입니다.

### Pleos variant에 필요한 설정

Pleos 실기 검증 전 다음을 확인해야 합니다.

1. Android Studio에서 Pleos Connect Emulator 2.1.0/API 34 AVD를 준비합니다.
2. JDK 17과 프로젝트 Gradle Wrapper를 사용합니다.
3. Pleos Maven 저장소에서 Vehicle 2.3.2와 NaviHelper 3.0.5 의존성을 받을 수 있어야 합니다.
4. 앱이 요청하는 최소 read 권한과 Navi route search 권한을 실제 SDK의 `@RequiresPermission`과 대조합니다.
5. Virtual Console로 P/D/R/N, driving restriction, service unavailable을 확인합니다.
6. Vehicle initialize/capability/callback/release와 NaviHelper initialize/request/event/release를 녹화·기록합니다.

관련 공식 문서:

- [공모전 개발 환경](https://appdevchallenge.auto-hmg.io/guide/development)
- [Vehicle SDK 2.3.2 설정](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/Vehicle/project-setup)
- [NaviHelper 3.0.5 설정](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/NaviHelper/importing-sdk)
- [NaviHelper 소개](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/NaviHelper/intro)

### 검증 완료 조건

`실제 SDK` 표시는 기능별 증거 범위에 한정합니다. 아래에서 완료된 NaviHelper 공개 목적지 1건은 application boundary의 제한적 실제 SDK 증거로 표시할 수 있지만, 체크되지 않은 Vehicle·복구·AWS 항목이나 앱 전체 실행 완료까지 뜻하지 않습니다.

- [x] Android Gradle build 성공 로그와 commit SHA (`4f2b559`)
- [x] Pleos AVD 설치·실행 및 instrumentation 51/51
- [ ] Vehicle capability와 P/D/R/N 상태 전환(PARK callback은 AVD 통과)
- [x] NaviHelper 공개 데모 목적지 요청과 `onRouteStarted` callback
- [ ] 앱 재시작 후 session/draft/queue 복구
- [ ] Android-AWS 인증·게시 E2E
- [ ] 비밀값·개인정보 비노출 확인

## APK 위치와 release 상태

공모전 제출 목표 경로:

```text
apk/팀명_SocialDrive_release.apk
```

현재 상태: **통합 소스와 fail-closed release pipeline 구현 · 승인된 signed artifact와 clean-install evidence 미완료**

- Demo/Pleos release는 동일 승인 인증서로 검증되는 원자적 세트여야 하며, 생성 전후 clean HEAD를 요구합니다. 현재 제출용 `apk/` 폴더에는 복사하지 않았습니다.
- GitHub Release 링크도 아직 없습니다.
- `팀명`은 최종 참가 팀명으로 교체해야 합니다.
- APK가 생성되면 깨끗한 Pleos AVD에서 설치·재실행한 뒤 SHA-256을 기록해야 합니다.
- 서명·깨끗한 AVD 설치·재실행·SHA-256 확인 전에는 제출 가능 release로 표시하지 않습니다.

공식 파일명과 제출 방식은 [공모전 지원·제출 방법](https://appdevchallenge.auto-hmg.io/guide/submission)을 따릅니다.

## 공모전 문서

- [공식 요구사항과 상태표](docs/contest/official-requirements.md)
- [2026 기획서 초안](docs/contest/2026-application-draft.md)
- [PT·시연 내부 권장안](docs/contest/pt-deck-and-demo.md)
- [제출 전 P0 안전 백로그](docs/contest/p0-submission-backlog.md)

## 운영 준비 메모 — 기존 안내 보존

앱 운영에 필요한 요소를 미리 활성화합니다.

외부 서버와 연결이 필요한 앱인 경우 심사 중 바로 서비스 연결이 가능하도록 앱 제출 전 앱 운영에 필요한 구성 요소들을 미리 활성화해주시기 바랍니다.

