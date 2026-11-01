# 2026 현대자동차그룹 차량용 앱 개발 공모전 공식 요구사항

기준일: 2026-11-01

대상 앱: Social Drive(소셜 드라이브)

이 문서는 제출 직전 확인해야 할 공식 사실과 저장소의 현재 증거 수준을 분리한다. 공식 페이지가 변경되면 공식 페이지를 우선하며, 문서에 적힌 버전·일정·제출 경로를 다시 확인한다.

## 1. 공식 일정

| 항목 | 공식 일정 | 준비 기준 |
|---|---|---|
| 접수 기간 | 2026-10-21 ~ **2026-11-15** | 내부 제출 마감은 `[2026-11-15 HH:MM KST]`로 별도 지정 |
| 1차 결과 발표 | 2026-11-23 | 결과 전까지 치명적 결함과 실기 증거 보완 |
| 2차 최종 PT | **2026-12-03 ~ 2026-12-04** | 실제 동작 앱, 발표 자료, 장애 대비 영상 준비 |
| 최종 결과 발표 | 2026-12-18 | 공지 변경 여부 확인 |

출처: [참가자 모집 공고](https://appdevchallenge.auto-hmg.io/notice/c0a1a000-2026-4923-8000-000000000001)

## 2. 제출 요구사항

### 필수

- 공모전 접수양식(기획서)
- 참가자 전원의 개인정보 수집·이용 동의 및 서명
- 이메일 제목: `[차량용 앱 개발 공모전] 팀명(개인은 성명)_대표자명`
- 접수 이메일: `appdevchallenge@hyundai-partners.com`

개인정보 값은 저장소에 채우지 않는다. 최종 제출본에서만 다음 placeholder를 교체한다.

- `[팀명]`
- `[대표자명]`
- `[참가 부문]`
- `[팀원 성명/소속/연락처/이메일]`
- `[서명일]`

### 선택 제출이지만 가산점 대상

- 프로토타입 또는 실제 앱 시연 영상
- GitHub 저장소 링크
- 소스 코드와 실행 가능한 release APK

APK는 GitHub 저장소의 `apk/` 폴더에 넣고 다음 파일명을 사용한다.

```text
apk/[팀명]_SocialDrive_release.apk
```

`README.md`에는 최소한 다음 내용을 포함해야 한다.

1. 앱 이름과 한 줄 소개
2. 실행 방법
3. 테스트 환경과 필요한 설정
4. APK 위치 또는 GitHub Release 링크

비공개 저장소를 제출하면 공지된 심사용 GitHub 계정을 Collaborator로 초대하고, 마감 전에 해당 계정의 접근을 확인해야 한다. GitHub 저장소와 APK는 1차에서 선택이지만 가산점 대상이며, 2차 PT 대상자는 PT 전에 실제 동작 가능한 앱을 완성해야 한다.

출처: [공식 지원·제출 방법](https://appdevchallenge.auto-hmg.io/guide/submission), [공식 FAQ](https://appdevchallenge.auto-hmg.io/faq)

## 3. 공식 평가축

| 평가축 | 공식 확인 질문 | Social Drive가 제출물에서 증명할 것 |
|---|---|---|
| 차량특화성 | 모바일 대비 차량 환경만의 독자 가치가 있는가? 주행·정차 및 차량 기능과 결합됐는가? | Vehicle 상태에 따른 안전 전환, 주행/주차 역할 분리, NaviHelper 경로 전달 |
| 창의성 | 운전자·동승자의 Pain Point가 명확하고 해결법이 독창적인가? | 장소 후기가 아닌 실제 경로와 구간별 하이라이트, 소비에서 실제 주행으로 이어지는 경험 |
| 기술 활용성 | Pleos SDK/API가 핵심 UX에 적절히 쓰였는가? | 실제 AVD에서 Vehicle SDK와 NaviHelper 초기화·capability·callback 증거 |
| 사업성 | 타깃, 예상 MAU, retention, 확대 가능성이 구체적인가? | 근거가 있는 MAU 산식, activation/W1/W4 지표, 콘텐츠 순환 구조 |
| 개발 산출물 가산점 | 영상·GitHub·소스·APK가 준비됐는가? | 재현 가능한 README, release APK, 실기 영상, 테스트 결과 |

세부 배점은 공개되지 않았다. 개발되지 않은 기능을 기획서의 실제 기능으로 표현해 기술 활용성을 부풀리지 않는다.

출처: [공식 심사·시상 안내](https://appdevchallenge.auto-hmg.io/guide/judging-awards)

## 4. 개발 환경 기준

- Android API 28 이상
- Gradle 8.0 이상
- Java 또는 Kotlin
- Pleos Connect 개발 문서와 대응하는 SDK 버전 사용
- 제출 실기 기준: Pleos Connect Emulator 2.1.0, Android API 34

공모전 개발 환경 출처: [공식 개발 환경](https://appdevchallenge.auto-hmg.io/guide/development)

Pleos SDK 출처: [Vehicle SDK 소개](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/Vehicle/intro), [NaviHelper 소개](https://document.pleos.ai/en/api-reference/connect-sdk-pleos/NaviHelper/intro)

## 5. SDK 상태 truth table

아래 표는 2026-11-01 공식 프로젝트 신청 가이드 기준이다. 제출 직전 Pleos Playground `Workspace > My Project`에서도 다시 확인한다.

| 기능 | 공식 상태 | 이번 MVP 표현 | 금지 사항 |
|---|---|---|---|
| Vehicle State / Vehicle SDK 2.3.2 | Ready to Use, 별도 신청 없이 사용 가능 | AVD 실기 검증 후 `실제 SDK` | 검증 전 실제 동작 완료로 표현 금지 |
| NaviHelper 3.0.5 | Ready to Use, 별도 신청 없이 사용 가능 | AVD에서 목적지·경유지 callback 검증 후 `실제 SDK` | 단순 UI 또는 fake 응답을 실제 SDK로 표현 금지 |
| FusedLocation | Coming Soon | `향후 확장` | 실제 위치 취득 기능으로 주장하거나 MVP 필수 의존성으로 사용 금지 |
| SpeechToText | Coming Soon | `향후 확장` | 실제 음성 인식 기능으로 주장 금지 |
| TextToSpeech | Coming Soon | `향후 확장` | 실제 음성 합성 기능으로 주장 금지 |
| Vehicle Control | Coming Soon | MVP 제외 | 차량 제어를 구현·시연했다고 주장 금지 |
| preset route / motion simulator | 앱 내부 데모 데이터 | 항상 `데모 데이터` 배지 표시 | 실제 센서·위치·Vehicle callback처럼 연출 금지 |

출처: [Pleos 프로젝트 생성 및 SDK 상태 가이드](https://document.pleos.ai/en/docs/pleos-only/create-my-project), [Vehicle SDK 2.3.2 설정](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/Vehicle/project-setup), [NaviHelper 3.0.5 설정](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/NaviHelper/importing-sdk)

## 6. 기능 표기 규칙

기획서·영상·PT의 모든 기능은 다음 네 등급 중 하나로 표시한다.

| 표기 | 정의 | 필요한 증거 |
|---|---|---|
| `실제 SDK` | Pleos SDK가 대상 AVD에서 실행됨 | 버전, capability, permission, callback, release가 보이는 녹화와 로그 |
| `앱 구현` | 저장소 코드와 로컬 테스트로 확인되지만 Pleos 실기와는 무관함 | commit, 테스트 명령과 결과, 화면 녹화 |
| `데모 데이터` | preset/fake로 동작함 | 화면에 지속적인 데모 표시 |
| `향후 기능` | 아직 제공되지 않거나 구현하지 않은 계획 | 실제 화면 흐름에 섞지 않고 로드맵에만 표시 |

## 7. 2026-11-01 저장소 증거 상태

| 영역 | 현재 위치/상태 | 현재 주장 가능한 수준 |
|---|---|---|
| 기본 `main` | 공모전 앱 기능 브랜치가 아직 병합되지 않은 기준점 | 오케스트레이터 기반만 존재하며 완성 앱으로 주장 불가 |
| Android Compose·Room·안전 게시 큐 | `agent/issue-android-foundation` 별도 작업 브랜치 | 구현 커밋과 테스트 소스가 존재함. main 병합·실기 실행 전 |
| AWS 최소 API | `agent/issue-backend-mvp` 별도 작업 브랜치 | API·보안 테스트 소스가 존재함. main 병합·배포·Android E2E 전 |
| Vehicle/NaviHelper 실제 연동 | 별도 구현 작업 진행 대상 | 설계 계약은 있으나 AVD 실행 증거 미확보 |
| release APK | 없음 | 제출 가능 상태 아님 |
| 시연 영상 | 없음 | 제출 가능 상태 아님 |

`테스트 소스가 존재한다`와 `해당 테스트를 실제로 실행해 통과했다`를 구분한다. 테스트 명령, 실행 시각, commit SHA, 결과가 남기 전에는 통과로 표현하지 않는다.

## 8. 제출 직전 공식 확인 체크리스트

- [ ] 공지사항에서 접수·PT 일정 변경 여부 확인
- [ ] Pleos Playground에서 SDK 상태 재확인 및 화면 캡처
- [ ] 개인정보 placeholder를 최종 제출 파일에서만 교체
- [ ] 기획서의 actual/demo/future 표기를 영상·PT와 일치시킴
- [ ] GitHub 심사용 계정 접근 확인
- [ ] release APK를 깨끗한 Pleos AVD에 설치·재실행
- [ ] `README.md` 필수 네 항목 확인
- [ ] APK 경로와 파일명 확인
- [ ] 이메일 첨부파일과 링크를 다른 환경에서 열어 봄
- [ ] 팀원 전원의 동의·서명·CC 확인
