# Social Drive | 소셜 드라이브

> 운전 중 좋았던 순간을 짧게 표시하고, 주차 후 드라이브 초안을 검토·공유하며, 다른 운전자의 코스를 차량 내비게이션으로 이어 달리는 것을 목표로 하는 차량용 앱입니다.

## 먼저 확인해 주세요

2026-11-01 현재 이 저장소의 `main`에는 Android 앱과 Pleos 연동 브랜치가 병합되지 않았습니다. 이 작업공간에도 실행할 `android/` 디렉터리와 release APK가 없습니다.

- Vehicle SDK 2.3.2와 NaviHelper 3.0.5 adapter의 최초 구현 커밋은 `6475287`이며 1차 독립 검토에서 `CHANGES_REQUIRED`였습니다.
- PLEOS·cloud·route-consent 기반 위에 충돌 검토를 추가한 최신 Android 작업 브랜치 HEAD는 `f091e995f3cbfd3edf87bbe6d5f705458f1b0d50`입니다. core Kotlin 63/63과 npm static 27/27이 통과했지만 `main`에는 병합되지 않았습니다.
- `f091e995`에는 Room v5의 durable 409 conflict 상태, 사용자의 명시적 최신 버전 확인·adopt, adopt 후 publish-only replay가 코드로 존재합니다. 일반 worker는 `FAILED/VERSION_CONFLICT`를 claim하지 못하며 자동 GET·자동 overwrite·자동 재게시를 하지 않습니다.
- `88b4938`은 최신 앱 HEAD가 아니라 P0-3A의 재현 가능한 local bootstrap/doctor와 offline zero-network fail-fast를 남긴 **역사적 증거**입니다.
- 위 검사는 SDK-independent core·정적 계약 범위입니다. `main` 미병합이며 concrete Cognito/API host 및 no-redirect HTTP adapter 설정이 없어 actual cloud는 사용할 수 없습니다. Gradle build·Room/Android instrumentation·Compose test·vendor adapter·APK·Pleos AVD·AWS E2E는 아직 실행되지 않았습니다.
- 최신 코드에는 recording session, `highlightCount`, draft, pending queue, preset reference와 route source·consent/withdraw·로컬 `DELETE_PENDING` 상태가 포함됩니다.
- 실제 위치·경로점 수집은 여전히 구현되지 않았습니다. 게시물 삭제도 주차 상태의 로컬 `DELETE_PENDING`까지만 구현됐고 network `DELETE`·retry는 아직 없습니다.
- P0-3A는 doctor/fail-fast 범위에서 부분 완료됐습니다. 프로젝트가 요구하는 Gradle wrapper 8.9 cache와 Android platform 35가 없고 `services.gradle.org` DNS가 차단되어 tasks·variants·assemble에는 도달하지 못했습니다.
- preset route와 fake motion은 실제 차량 데이터가 아닌 데모 데이터입니다.

따라서 현재 저장소만 clone해서는 앱을 실행할 수 없습니다. 아래 명령은 Android/Pleos 브랜치가 `main`에 병합되고 실제로 빌드·실행 검증된 뒤 사용할 **예정 실행 절차**입니다.

## 3분 확인 순서

1. [현재 기능 상태](#현재-기능-상태)에서 actual/demo/future 구분을 확인합니다.
2. [실행 방법](#실행-방법)에서 현재 실행 가능 여부와 예정 명령을 확인합니다.
3. [테스트 환경과 설정](#테스트-환경과-필요한-설정)에서 Pleos AVD 요구사항을 확인합니다.
4. [APK 상태](#apk-위치와-release-상태)에서 APK가 아직 생성되지 않았음을 확인합니다.

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

| 등급 | 기능 | 2026-11-01 증거 |
|---|---|---|
| `실제 SDK` | 없음 | Pleos AVD callback과 release 증거가 아직 없음 |
| `코드 존재(미통합)` | Vehicle/NaviHelper + cloud + route-consent + conflict review | 최신 `f091e995`; core Kotlin 63/63, npm static 27/27. 409 durable stop, explicit adopt, publish-only replay 코드 포함. `main` 미병합, Gradle·Room instrumentation·Compose·APK·AVD·cloud 미실행 |
| `역사적 도구 증거` | P0-3A runtime bootstrap/doctor | `88b4938`; offline zero-network fail-fast 확인. tasks/variants/assemble 또는 앱 실행 성공 증거가 아님 |
| `코드 존재(미통합)` | session/draft/queue, route source, consent/withdraw, local delete state | 실제 위치 수집 없이 demo/unavailable을 분리하고 게시물 삭제는 parked-only local `DELETE_PENDING`까지만 구현 |
| `데모 데이터` | preset route / fake motion | 실제 위치나 실제 Vehicle callback이 아님 |
| `미구현` | 실제 위치·경로점 수집, network `DELETE`·retry, release APK | 구현 또는 검증 증거 없음 |
| `향후 기능` | FusedLocation, SpeechToText, TextToSpeech, Vehicle Control | 공식 Coming Soon이며 이번 MVP의 실제 기능으로 사용하지 않음 |

SDK 제공 상태는 [Pleos 프로젝트 및 SDK 상태 가이드](https://document.pleos.ai/en/docs/pleos-only/create-my-project)를 기준으로 하며, Vehicle과 NaviHelper의 구현 가능 여부와 앱에서 실제 검증됐는지는 별개입니다.

## 실행 방법

### 현재 상태

현재 `main`과 이 제출 문서 작업공간에는 Android 앱이 병합되지 않아 실행 명령을 수행할 수 없습니다.

```bash
test -d android && echo "Android source present" || echo "Android source not merged"
test -f apk/팀명_SocialDrive_release.apk && echo "Release APK present" || echo "Release APK not created"
```

예상 결과:

```text
Android source not merged
Release APK not created
```

### Android 브랜치 병합 후 예정 절차

다음 명령은 아직 이 작업공간에서 성공 검증되지 않았습니다. Android 소스가 `main`에 병합된 뒤 실제 모듈·variant 이름과 산출물 경로를 다시 확인해야 합니다.

```bash
cd android

# 데모 데이터 기반 variant 빌드 예정 명령
./gradlew :app:assembleDemoDebug

# Pleos SDK 연동 variant 빌드 예정 명령
./gradlew :app:assemblePleosDebug
```

예정 설치 예시:

```bash
adb devices
adb install -r app/build/outputs/apk/demo/debug/app-demo-debug.apk
```

위 APK 경로 역시 빌드가 통과한 뒤 실제 출력과 대조해야 합니다. 현재 성공 로그나 설치 증거는 없습니다.

## 테스트 환경과 필요한 설정

| 항목 | 제출 목표 | 현재 검증 상태 |
|---|---|---|
| OS | macOS 개발 환경 | 로컬 호스트 사용 중 |
| JDK | JDK 17 | 17.0.13 설치 확인; Android Gradle build는 미실행 |
| Android | minSdk 28 이상, API 34 Pleos AVD | Pleos API34/arm64 AVD 정의는 있으나 project bootstrap이 요구하는 exact Android platform 35는 없음; boot 미성공 |
| Gradle | 프로젝트 wrapper 8.9 | 요구되는 8.9 distribution cache 없음. 호스트의 별도 8.14.3 cache는 프로젝트 wrapper 요구를 충족하지 않음; tasks·variants·assemble 미도달 |
| Emulator | Pleos Connect Emulator 2.1.0, Cold Boot, RAM 4GB | AVD/system image와 RAM 4GB 확인. sandbox CPU feature 제약으로 `emulator -version` 실패; boot·앱 실행 증거 없음 |
| Vehicle | Vehicle SDK 2.3.2 / Vehicle Service 대응 버전 | `f091e995`이 PLEOS 기반 코드를 포함; vendor adapter·Gradle·AVD callback 미실행 |
| Navigation | NaviHelper 3.0.5 | `f091e995`이 NaviHelper 기반 코드를 포함; vendor adapter·Gradle·route callback 미실행 |
| AWS | Cognito JWT, DynamoDB, private S3 기반 목표 | backend `ddbd61f` 계약 포함. concrete Cognito/API host·no-redirect HTTP adapter 설정 부재로 actual cloud unavailable; AWS E2E 미실행 |

P0-3A bootstrap/doctor는 missing wrapper 8.9와 platform 35를 진단하고 offline 실행에서 네트워크 요청 없이 즉시 실패하도록 검증됐다. 현재 `services.gradle.org` DNS가 차단되어 wrapper를 받을 수 없고 AGP·Kotlin Gradle plugin·Pleos vendor artifact도 준비되지 않았다. build·emulator·APK 성공을 주장할 수 없으며 signing secret과 keystore는 조회하지 않았다.

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

- [ ] Android Gradle build 성공 로그와 commit SHA
- [ ] Pleos AVD 설치·실행 화면
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

현재 상태: **생성 전**

- `apk/` 폴더와 release APK는 아직 없습니다.
- GitHub Release 링크도 아직 없습니다.
- `팀명`은 최종 참가 팀명으로 교체해야 합니다.
- APK가 생성되면 깨끗한 Pleos AVD에서 설치·재실행한 뒤 SHA-256을 기록해야 합니다.
- APK가 없는데 다운로드 링크나 실행 가능 상태를 표시하지 않습니다.

공식 파일명과 제출 방식은 [공모전 지원·제출 방법](https://appdevchallenge.auto-hmg.io/guide/submission)을 따릅니다.

## 공모전 문서

- [공식 요구사항과 상태표](docs/contest/official-requirements.md)
- [2026 기획서 초안](docs/contest/2026-application-draft.md)
- [PT·시연 내부 권장안](docs/contest/pt-deck-and-demo.md)
- [제출 전 P0 안전 백로그](docs/contest/p0-submission-backlog.md)

## 운영 준비 메모 — 기존 안내 보존

앱 운영에 필요한 요소를 미리 활성화합니다.

외부 서버와 연결이 필요한 앱인 경우 심사 중 바로 서비스 연결이 가능하도록 앱 제출 전 앱 운영에 필요한 구성 요소들을 미리 활성화해주시기 바랍니다.

