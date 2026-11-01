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

아래 네 평가축은 공식 페이지에 공개된 **1차 서류심사 기준**이다. 2차 PT 심사 기준은 공식 페이지에서 추후 안내 예정으로 명시되어 있다. 이 저장소에서 PT 슬라이드를 네 평가축에 연결하는 것은 발표 준비를 위한 내부 전략이지, 공개되지 않은 2차 평가 기준을 뜻하지 않는다.

| 평가축 | 공식 확인 질문 | Social Drive가 제출물에서 증명할 것 |
|---|---|---|
| 차량특화성 | 모바일 대비 차량 환경만의 독자 가치가 있는가? 주행·정차 및 차량 기능과 결합됐는가? | Vehicle 상태에 따른 안전 전환, 주행/주차 역할 분리, NaviHelper 경로 전달 |
| 창의성 | 운전자·동승자의 Pain Point가 명확하고 해결법이 독창적인가? | 장소 후기가 아닌 실제 경로와 구간별 하이라이트, 소비에서 실제 주행으로 이어지는 경험 |
| 기술 활용성 | Pleos SDK/API가 핵심 UX에 적절히 쓰였는가? | 실제 AVD에서 Vehicle SDK와 NaviHelper 초기화·capability·callback 증거 |
| 사업성 | 타깃, 예상 MAU, retention, 확대 가능성이 구체적인가? | 근거가 있는 MAU 산식, activation/W1/W4 지표, 콘텐츠 순환 구조 |
| 개발 산출물 가산점 | 영상·GitHub·소스·APK가 준비됐는가? | 재현 가능한 README, release APK, 실기 영상, 테스트 결과 |

1차 세부 배점은 공개되지 않았고 2차 PT 심사 기준도 아직 공개되지 않았다. 개발되지 않은 기능을 기획서의 실제 기능으로 표현해 기술 활용성을 부풀리지 않는다.

출처: [공식 심사·시상 안내](https://appdevchallenge.auto-hmg.io/guide/judging-awards)

## 4. 개발 환경 기준

- Android API 28 이상
- Gradle 8.0 이상
- Java 또는 Kotlin
- Pleos Connect 개발 문서와 대응하는 SDK 버전 사용
- 제출 실기 기준: Pleos Connect Emulator 2.1.0, Android API 34

공모전 개발 환경 출처: [공식 개발 환경](https://appdevchallenge.auto-hmg.io/guide/development)

Pleos SDK 출처: [Vehicle SDK 소개](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/Vehicle/intro), [NaviHelper 소개](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/NaviHelper/intro)

## 5. SDK 상태 truth table

아래 표는 2026-11-01 공식 프로젝트 신청 가이드 기준이다. 제출 직전 Pleos Playground `Workspace > My Project`에서도 다시 확인한다.

| 기능 | 공식 상태 | 2026-11-01 저장소 등급 | 승격 조건/금지 사항 |
|---|---|---|---|
| Vehicle State / Vehicle SDK 2.3.2 | Ready to Use, 별도 신청 없이 사용 가능 | `코드 존재(미통합)` | Android HEAD `88b4938`이 PLEOS `89078a0`을 포함하며 Sentinel `APPROVE`, core Kotlin 51/51, npm 26/26. tasks/variants/assemble·vendor adapter·instrumentation·APK·AVD 미실행. capability/callback 증거 후에만 `실제 SDK` |
| NaviHelper 3.0.5 | Ready to Use, 별도 신청 없이 사용 가능 | `코드 존재(미통합)` | Android HEAD `88b4938`이 PLEOS `89078a0`을 포함하며 Sentinel `APPROVE`, core Kotlin 51/51, npm 26/26. tasks/variants/assemble·vendor adapter·instrumentation·APK·AVD 미실행. 실제 route callback 증거 후에만 `실제 SDK` |
| FusedLocation | Coming Soon | `향후 기능` | 실제 위치·경로점 수집 기능으로 주장하거나 MVP 필수 의존성으로 사용 금지 |
| SpeechToText | Coming Soon | `향후 기능` | 실제 음성 인식 기능으로 주장 금지 |
| TextToSpeech | Coming Soon | `향후 기능` | 실제 음성 합성 기능으로 주장 금지 |
| Vehicle Control | Coming Soon | `향후 기능` | 차량 제어를 구현·시연했다고 주장 금지 |
| preset route / motion simulator | 앱 내부 데모 데이터 | 항상 `데모 데이터` 배지 표시 | 실제 센서·위치·Vehicle callback처럼 연출 금지 |

출처: [Pleos 프로젝트 생성 및 SDK 상태 가이드](https://document.pleos.ai/en/docs/pleos-only/create-my-project), [Vehicle SDK 2.3.2 설정](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/Vehicle/project-setup), [NaviHelper 3.0.5 설정](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/NaviHelper/importing-sdk)

## 6. 기능 표기 규칙

기획서·영상·PT의 모든 기능은 다음 여섯 등급 중 하나로 표시하며, 표 밖에서 별도 상태명을 만들지 않는다.

| 표기 | 정의 | 필요한 증거 |
|---|---|---|
| `실제 SDK` | Pleos SDK가 대상 AVD에서 실행됨 | 버전, capability, permission, callback, release가 보이는 녹화와 로그 |
| `앱 구현` | 현재 제출 브랜치에 통합되고 실행 검증된 앱 기능 | commit, 실행한 테스트 명령과 결과, 화면 녹화 |
| `코드 존재(미통합)` | 별도 작업 브랜치에 코드가 있으나 제출 브랜치 통합·실행 증거가 없음 | branch/commit과 남은 통합·검증 항목 명시 |
| `데모 데이터` | preset/fake로 동작함 | 화면에 지속적인 데모 표시 |
| `미구현` | 이번 제출 목표지만 현재 코드 또는 실행 증거가 없음 | 구현·검증 전 현재형 서술 금지 |
| `향후 기능` | Coming Soon이거나 이번 MVP 범위 밖의 계획 | 실제 화면 흐름에 섞지 않고 로드맵에만 표시 |

## 7. 2026-11-01 저장소 증거 상태

| 영역 | 현재 위치/상태 | 현재 주장 가능한 수준 |
|---|---|---|
| 기본 `main` | 공모전 앱 기능 브랜치가 아직 병합되지 않은 기준점 | 오케스트레이터 기반만 존재하며 완성 앱으로 주장 불가 |
| Android local/consent 기반 | Android HEAD `88b4938` (`b1c33a2` 포함) | `코드 존재(미통합)`. session/draft/queue, route source, consent/withdraw, Room v4, parked-only local `DELETE_PENDING` 코드. network `DELETE`·retry는 없음 |
| 실제 경로점·위치 수집 | 확인된 구현 증거 없음 | `미구현`. consent 안전 경계가 있어도 실제 위치 샘플·route point를 수집한다고 주장 금지 |
| Android cloud contract | Android HEAD `88b4938` + backend 계약 `ddbd61f` | `코드 존재(미통합)`. core Kotlin 51/51, npm 26/26, Room v4·`수동 SQLite predicate/migration PASS`. concrete Cognito/API 설정 부재로 actual cloud unavailable |
| Vehicle/NaviHelper adapter | Android HEAD `88b4938`이 PLEOS `89078a0` 포함 | `코드 존재(미통합)`. Sentinel `APPROVE`; tasks/variants/assemble·vendor adapter·instrumentation·APK·Pleos AVD runtime 미실행. `실제 SDK` 증거 아님 |
| P0-3A bootstrap/doctor | Android HEAD `88b4938` | `코드 존재(미통합)`. 재현 가능한 bootstrap/doctor 추가, offline zero-network fail-fast 검증. project-required wrapper 8.9 cache와 exact platform 35가 없고 `services.gradle.org` DNS가 차단되어 tasks 단계 미도달 |
| root `readme.md` | 앱 소개·실행 방법·테스트 환경/설정·APK 목표 경로를 문서화 | 문서 준비 완료. Android/main 미병합·빌드/AVD 미검증·APK 생성 전 상태를 명시함 |
| release APK | 없음 | `미구현`. 제출 가능 상태 아님 |
| 시연 영상 | 없음 | `미구현`. 선택 가산점 산출물 준비 전 |

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
