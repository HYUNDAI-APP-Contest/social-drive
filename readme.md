# Social Drive | 소셜 드라이브

> 운전 중 좋았던 순간을 짧게 표시하고, 주차 후 드라이브 초안을 검토·공유하며, 다른 운전자의 코스를 차량 내비게이션으로 이어 달리는 것을 목표로 하는 차량용 앱입니다.

## 먼저 확인해 주세요

2026-11-01 현재 이 저장소의 `main`에는 Android 앱과 Pleos 연동 브랜치가 병합되지 않았습니다. 이 작업공간에도 실행할 `android/` 디렉터리와 release APK가 없습니다.

- Vehicle SDK 2.3.2와 NaviHelper 3.0.5 adapter의 최초 구현 커밋은 `6475287`이며 1차 독립 검토에서 `CHANGES_REQUIRED`였습니다.
- race/lifecycle/permission 지적은 `98a2978`과 `6e930d6`에서 보완됐고, 현재 최신 커밋은 `89078a0`입니다.
- `89078a0`은 독립 검토 `APPROVE`이며 Android Studio 내장 Kotlin compiler로 production core compile, JVM 테스트 15/15, npm 테스트 21/21을 통과했습니다.
- 이 `APPROVE`는 vendor adapter나 런타임 성공을 뜻하지 않습니다. PLEOS 브랜치는 `main` 미병합이며 vendor adapter compile, Gradle assemble, APK 생성, Pleos AVD runtime/callback은 아직 검증되지 않았습니다.
- 현재 확인된 Android 영속 데이터 범위는 recording session, `highlightCount`, draft, pending queue, preset reference입니다.
- 실제 위치·경로점 수집과 위치정보 동의·철회 UI는 아직 구현되지 않았습니다.
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
| `코드 존재(미통합)` | Vehicle SDK 2.3.2 / NaviHelper 3.0.5 adapter | 최신 `89078a0`; 독립 검토 `APPROVE`, production core compile, JVM 15/15, npm 21/21. `main` 미병합, vendor adapter compile·Gradle assemble·APK·AVD runtime 미검증 |
| `코드 존재(미통합)` | recording session, `highlightCount`, draft, pending queue, preset reference | 별도 Android 작업 코드에 존재하나 제출 브랜치 통합·실행 증거 전 |
| `데모 데이터` | preset route / fake motion | 실제 위치나 실제 Vehicle callback이 아님 |
| `미구현` | 실제 위치·경로점 수집, 위치 동의·철회 UI, release APK | 구현 또는 검증 증거 없음 |
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
| JDK | JDK 17 | Android 브랜치 빌드 미검증 |
| Android | minSdk 28 이상, API 34 Pleos AVD | AVD 실행 미검증 |
| Gradle | 프로젝트 Gradle Wrapper, Gradle 8 이상 | production core Kotlin은 별도 compiler로 검증됨. Gradle assemble과 vendor adapter compile은 미검증 |
| Emulator | Pleos Connect Emulator 2.1.0, Cold Boot, RAM 4GB | 설치·부팅·앱 실행 증거 없음 |
| Vehicle | Vehicle SDK 2.3.2 / Vehicle Service 대응 버전 | 최신 `89078a0`, 독립 검토 `APPROVE`; production core compile·JVM 15/15, vendor adapter compile·Gradle assemble·AVD callback 미검증 |
| Navigation | NaviHelper 3.0.5 | 최신 `89078a0`, 독립 검토 `APPROVE`; production core compile·JVM 15/15, vendor adapter compile·Gradle assemble·route callback 미검증 |
| AWS | Cognito JWT, DynamoDB, private S3 기반 목표 | backend 승인 HEAD `ddbd61f`; main 미병합, Android 통합·배포 E2E 미검증 |

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

