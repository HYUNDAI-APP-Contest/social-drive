# Social Drive | 소셜 드라이브

> 운전 중 좋았던 순간을 짧게 표시하고, 주차 후 드라이브 초안을 검토·공유하며, 다른 운전자의 코스를 차량 내비게이션으로 이어 달리는 것을 목표로 하는 차량용 앱입니다.

## 먼저 확인해 주세요

2026-11-02 현재 Android 앱은 검토 완료 브랜치 `agent/issue-conflict-review`와 PR #2에 있으며, 아직 `main`에는 병합되지 않았습니다. 이 제출 문서 작업공간 자체에는 `android/` 디렉터리와 제출용 서명 release APK가 없습니다.

- Vehicle SDK 2.3.2와 NaviHelper 3.0.5 adapter의 최초 구현 커밋은 `6475287`이며 1차 독립 검토에서 `CHANGES_REQUIRED`였습니다.
- 최신 Android 검토 HEAD는 `dd3d61dabda9d69526227a84a40fce20c7bc3833`입니다. PLEOS Connect Emulator 2.1.0(Android 14, user 10)에서 instrumentation 51/51, SDK-independent Kotlin 74/74, 정적 계약 29/29이 통과했습니다.
- Gradle clean, Demo/Pleos 단위 테스트, Demo release lint, Demo Debug/Release, Pleos Debug와 Pleos AndroidTest 조립이 179개 task로 성공했습니다. Demo release 산출물은 unsigned이므로 공모전 제출용 서명·설치 검증은 아직 남아 있습니다.
- Room v6에는 durable 409 conflict review와 parked-only published-delete tombstone, bounded retry가 구현돼 있습니다. Pleos/production cloud factory는 승인된 Cognito/API 설정과 concrete transport가 없으면 fail closed합니다.
- `88b4938`은 최신 앱 HEAD가 아니라 P0-3A의 재현 가능한 local bootstrap/doctor와 offline zero-network fail-fast를 남긴 **역사적 증거**입니다.
- `main` 미병합이며 concrete Cognito/API host 및 no-redirect HTTP adapter 설정이 없어 actual cloud와 AWS E2E는 아직 사용할 수 없습니다. 실제 PLEOS system Back이 embedded child task에 전달되는지는 `UNRESOLVED`이며, 자동 UI 테스트는 resumed `MainActivity` dispatcher에 Back이 전달된 이후의 Compose 계약만 증명합니다.
- 최신 코드에는 recording session, `highlightCount`, draft, pending queue, preset reference와 route source·consent/withdraw·로컬 `DELETE_PENDING` 상태가 포함됩니다.
- 실제 위치·경로점 수집은 여전히 구현되지 않았습니다. 게시물 삭제는 Room v6 tombstone과 network `DELETE`·bounded retry 계약까지 구현·계측됐지만, actual cloud 설정이 없어 demo/fake transport 증거입니다.
- Gradle 8.9과 Android platform 35가 준비되어 실제 조립과 PLEOS AVD 계측까지 완료됐습니다. 역사적 `88b4938`의 bootstrap 차단 기록은 현재 상태가 아니라 과거 복구 증거로만 유지합니다.
- preset route와 fake motion은 실제 차량 데이터가 아닌 데모 데이터입니다.

따라서 `main`만 clone하면 아직 앱을 실행할 수 없습니다. 아래 명령은 검토 브랜치에서 성공했으며 PR #2 병합 뒤 `main`에서도 동일하게 재검증해야 합니다.

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
4. Vehicle/NaviHelper 검증 후 공개 코스의 목적지·경유지를 차량 Navigation 앱으로 전달합니다.

### 핵심 안전 원칙

- `Driving`뿐 아니라 상태를 확인할 수 없는 `Unknown`에서도 편집·게시를 차단하는 것이 목표입니다.
- 실제 위치 기능을 추가할 때는 명시적 동의·철회와 공개 범위를 먼저 구현해야 합니다.
- 게시를 확정하기 전에는 데이터를 로컬에 보존하고, 사용자가 선택한 정보만 업로드하는 구조를 목표로 합니다.
- SDK callback이나 AVD 증거가 없는 기능을 실제 차량 연동으로 표시하지 않습니다.

## 현재 기능 상태

| 등급 | 기능 | 2026-11-02 증거 |
|---|---|---|
| `코드 존재(미통합)` | Driving/Unknown 잠금, recording/draft/queue, consent/withdraw, 409 review, published delete | `dd3d61d`; 검토 브랜치에서 PLEOS instrumentation 51/51, Kotlin 74/74, static 29/29, Gradle build/lint 성공. `main` 병합·actual cloud는 미완료 |
| `코드 존재(미통합)` | Pleos flavor 초기화·수명주기, Compose 안전 UI, Room/WorkManager | Pleos Connect Android 14 user 10에서 브랜치 실행 검증. 실제 Vehicle 상태 read/callback과 physical system Back 전달은 증명하지 않음 |
| `역사적 도구 증거` | P0-3A runtime bootstrap/doctor | `88b4938`; offline zero-network fail-fast 확인. tasks/variants/assemble 또는 앱 실행 성공 증거가 아님 |
| `코드 존재(미통합)` | Vehicle/NaviHelper와 cloud production 경계 | vendor 초기화/수명주기와 fail-closed production port는 존재. 실제 Vehicle callback·Navi route callback·actual cloud는 별도 증거 필요 |
| `데모 데이터` | preset route / fake motion | 실제 위치나 실제 Vehicle callback이 아님 |
| `미구현` | 실제 위치·경로점 수집, signed submission release APK, actual cloud E2E | 구현 또는 제출 검증 증거 없음 |
| `향후 기능` | FusedLocation, SpeechToText, TextToSpeech, Vehicle Control | 공식 Coming Soon이며 이번 MVP의 실제 기능으로 사용하지 않음 |

SDK 제공 상태는 [Pleos 프로젝트 및 SDK 상태 가이드](https://document.pleos.ai/en/docs/pleos-only/create-my-project)를 기준으로 하며, Vehicle과 NaviHelper의 구현 가능 여부와 앱에서 실제 검증됐는지는 별개입니다.

## 실행 방법

### 현재 상태

현재 `main`과 이 제출 문서 작업공간에는 Android 앱이 병합되지 않아 실행 명령을 수행할 수 없습니다.

```bash
test -d android && echo "Android source present" || echo "Android source not merged"
test -f apk/팀명_SocialDrive_release.apk && echo "Submission release APK present" || echo "Submission release APK not created"
```

예상 결과:

```text
Android source not merged
Submission release APK not created
```

### Android 브랜치 병합 후 예정 절차

다음 명령은 Android 검토 브랜치 `dd3d61d`에서 성공했습니다. `main` 병합 뒤 다시 실행해야 합니다.

```bash
cd android

# 데모 데이터 기반 variant
./gradlew :app:assembleDemoDebug

# Pleos SDK 연동 variant
./gradlew :app:assemblePleosDebug
```

예정 설치 예시:

```bash
adb devices
adb install -r app/build/outputs/apk/demo/debug/app-demo-debug.apk
```

Demo/Pleos debug APK와 Demo unsigned release APK 생성은 확인했습니다. 제출용 release APK는 서명 후 깨끗한 PLEOS AVD에서 다시 설치·실행해야 합니다.

## 테스트 환경과 필요한 설정

| 항목 | 제출 목표 | 현재 검증 상태 |
|---|---|---|
| OS | macOS 개발 환경 | 로컬 호스트 사용 중 |
| JDK | JDK 17 | Gradle build에서 사용·성공 |
| Android | minSdk 28 이상, compile platform 35 | Android platform 35로 Demo/Pleos 조립 성공 |
| Gradle | 프로젝트 wrapper 8.9 | clean/unit/lint/assemble 179 tasks 성공 |
| Emulator | Pleos Connect Emulator 2.1.0, Android 14, user 10 | 앱 설치·UI 8/8·비UI 43/43 성공. physical system Back은 미검증 |
| Vehicle | Vehicle SDK 2.3.2 / Vehicle Service 대응 버전 | 초기화·release 수명주기와 fail-closed Unknown 실행. 실제 permissioned state callback은 미검증 |
| Navigation | NaviHelper 3.0.5 | 초기화·request coordinator 계약은 빌드/코어 검증. 실제 route callback 실기 증거는 미완료 |
| AWS | Cognito JWT, DynamoDB, private S3 기반 목표 | backend `ddbd61f` 계약 포함. concrete Cognito/API host·no-redirect HTTP adapter 설정 부재로 actual cloud unavailable; AWS E2E 미실행 |

P0-3A bootstrap/doctor의 과거 차단은 해소되어 build·emulator·debug APK 증거가 생겼습니다. signing secret과 keystore는 조회하지 않았고, 제출용 signed release와 actual cloud는 여전히 별도 승인·검증 경계입니다.

### Pleos variant에 필요한 설정

Android 소스가 병합되면 다음을 확인해야 합니다.

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

아래 증거가 모두 생기기 전에는 `실제 SDK` 또는 실행 완료로 표시하지 않습니다.

- [x] Android Gradle build 성공 로그와 commit SHA (`dd3d61d`)
- [x] Pleos AVD 설치·실행 및 instrumentation 51/51
- [ ] Vehicle capability와 P/D/R/N 상태 전환
- [ ] NaviHelper 목적지·경유지 요청과 callback
- [ ] 앱 재시작 후 session/draft/queue 복구
- [ ] Android-AWS 인증·게시 E2E
- [ ] 비밀값·개인정보 비노출 확인

## APK 위치와 release 상태

공모전 제출 목표 경로:

```text
apk/팀명_SocialDrive_release.apk
```

현재 상태: **Debug와 unsigned release 생성 완료 · 제출용 signed release 미완료**

- Android 작업 브랜치에는 Demo/Pleos debug와 Demo unsigned release 산출물이 있으나, 제출용 `apk/` 폴더에는 아직 복사하지 않았습니다.
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

