# RoadStory

> 좋은 길 위에서 만난 **영상·음악·창문·조명의 순간을 기록하고, 다음 드라이버가 자신의 차에서 다시 경험하도록 연결하는 PLEOS 소셜 드라이브 앱**입니다.

RoadStory는 드라이브 코스를 단순히 안내하는 데서 그치지 않습니다. 주행 중에는 버튼 또는 Gleo 음성 명령으로 인상적인 순간을 남기고, 주차 후 안전하게 완성해 공유합니다. 다른 운전자는 공개된 드라이브 카드를 탐색하고 30초 하이라이트를 경험한 뒤, 마음에 든 코스를 PLEOS 내비게이션으로 바로 시작할 수 있습니다.

## 핵심 기능

- **드라이브 기록**: `방금 좋았어` 버튼과 Gleo 음성 명령으로 주행 중 하이라이트 저장
- **30초 하이라이트 경험**: 도로 장면·음악 정보·창문·앰비언트 조명 큐를 하나의 시퀀스로 재생
- **코스 탐색 및 내비게이션 연결**: 공유된 드라이브 카드를 확인하고 NaviHelper로 경로 안내 시작
- **주행 안전 제어**: Vehicle SDK로 PARK/주행/미확인 상태를 구분하고 편집·게시 기능을 안전하게 제한
- **오프라인 우선 저장**: Room과 WorkManager로 기록·초안·게시 작업을 보존하고 재시작 후 이어서 처리

## 실행 방법

### 1. 실행 환경

- macOS
- JDK 17
- Android SDK 35
- PLEOS Connect Emulator 2.1.0 — Android 14(API 34), 2560 × 1440

### 2. 앱 빌드 및 실행

저장소를 내려받은 뒤 PLEOS Connect Emulator를 실행하고 다음 명령을 사용합니다.

```bash
cd android
./PLEOS-앱-실행.command
```

스크립트는 `pleosDebug` APK를 빌드한 뒤 연결된 PLEOS Emulator에 설치하고 RoadStory를 실행합니다. PLEOS SDK 없이 UI와 기본 흐름만 확인하려면 아래 명령을 사용합니다.

```bash
cd android
bash scripts/gradle-local.sh :app:assembleDemoDebug
```

생성 파일: `android/app/build/outputs/apk/demo/debug/app-demo-debug.apk`

## 테스트 환경 및 필요한 설정

| 항목 | 내용 |
|---|---|
| 앱 패키지 | `com.socialdrive.app` |
| Android | minSdk 28 / targetSdk 35 / compileSdk 35 |
| PLEOS SDK | Vehicle SDK 2.3.2, NaviHelper 3.0.5, Fused Location, STT/TTS, Gleo Interface |
| 로컬 데이터 | Room, WorkManager |
| 클라우드 | Amazon Cognito, API Gateway, Lambda, DynamoDB, private S3 |

PLEOS 기능 검증에는 PLEOS Maven 의존성에 접근 가능한 환경과 PLEOS Connect Emulator가 필요합니다. 클라우드 설정이 없거나 차량 상태를 확인할 수 없는 경우 앱은 해당 기능을 임의로 실행하지 않고 안전하게 제한합니다. Demo variant의 경로·차량 상태·미디어는 기능 확인용 데모 데이터입니다.

## APK 및 저장소

- **GitHub 저장소**: https://github.com/HYUNDAI-APP-Contest/social-drive
- **제출 APK 다운로드**: [용용_RoadStory_release.apk](apk/용용_RoadStory_release.apk)
- **SHA-256 체크섬**: [용용_RoadStory_release.apk.sha256](apk/용용_RoadStory_release.apk.sha256)

| 항목 | 검증 결과 |
|---|---|
| 파일 크기 | 33,884,939 bytes (GitHub 저장소에 직접 업로드 가능) |
| 패키지 | `com.socialdrive.app` |
| 버전 | `0.1.0` (`versionCode 1`) |
| SHA-256 | `58c659ef7b3d097dc052950c332cfd069e99941973022d96deb8af2505e1ea99` |
| 릴리스 검증 | APK Signature Scheme v3, `debuggable=false`, 백업·평문 통신 차단 |

공모전 제출용 **서명된 release APK**가 위 경로에 포함되어 있습니다. debug APK나 unsigned APK가 아닌 링크된 파일을 사용해 주세요.

## 주요 기술 구성

```text
RoadStory Android App
├─ PLEOS Vehicle SDK · NaviHelper · Fused Location · STT/TTS · Gleo
├─ Room · WorkManager
└─ Amazon Cognito · API Gateway · Lambda · DynamoDB · S3
```

앱은 원본 위·경도와 인증 토큰을 로컬 게시 데이터나 로그에 남기지 않습니다. 차량 제어는 사용자 동의, PARK 상태, 권한과 capability가 모두 확인될 때만 허용하며, 지원되지 않는 환경에서는 화면 기반 데모 경험으로 구분합니다.

## 검증 명령

```bash
cd android
bash scripts/gradle-local.sh :app:testDemoDebugUnitTest :app:testPleosDebugUnitTest
bash scripts/gradle-local.sh :app:assembleDemoDebug :app:assemblePleosDebug
```

제출용 release 생성과 clean-install 검증 절차는 [APK 안내](apk/README.md)를 참고해 주세요.
