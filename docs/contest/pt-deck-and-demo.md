# Social Drive PT 및 시연 계획 — 내부 권장 7장 구성

기준일: 2026-11-01

팀명: `[팀명]`

발표자: `[발표자명]`

## 1. 발표 원칙

7장 슬라이드, 90~150초 영상, 5분·7분 리허설 버전은 공모전 공식 규격이 아니라 팀의 **내부 권장안**이다. 공식 2차 PT 심사 기준과 발표 시간은 추후 안내 예정이므로 공지가 나오면 이 구성을 조정한다.

1. 실제 앱 화면과 실제 실행 증거를 먼저 보여 준다.
2. `실제 SDK`, `앱 구현`, `코드 존재(미통합)`, `데모 데이터`, `미구현`, `향후 기능`을 같은 색상 체계로 끝까지 구분한다.
3. Vehicle/NaviHelper adapter는 최초 `6475287` 1차 반려 후 수정한 `98a2978`에 `코드 존재(미통합)` 상태다. 독립 재검토 진행 중과 Gradle·AVD 미검증을 표시하고, `APPROVE`와 AVD callback 증거가 생긴 뒤에만 `실제 SDK`로 승격한다.
4. FusedLocation, STT, TTS, Vehicle Control은 실제 기능으로 말하지 않는다.
5. MAU와 retention은 산식·검증 방법을 제시하고 근거 없는 숫자를 만들지 않는다.

## 2. 내부 권장 7장 발표 덱

공식 네 평가축은 1차 서류심사 기준이다. 아래 슬라이드의 평가축 연결은 2차 공식 배점을 뜻하지 않고, 메시지를 일관되게 만드는 내부 발표 전략이다. 공식 2차 PT 기준은 추후 안내를 확인한다. [공식 심사 안내](https://appdevchallenge.auto-hmg.io/guide/judging-awards)

### 1장 — 한 번의 터치가 다음 사람의 드라이브가 된다

화면:

- Social Drive 로고와 앱명
- 하이라이트 버튼 → 공유 코스 → Navi 안내의 3단 이미지
- `[팀명] / [대표자명] / [참가 부문]`

발표 문장:

> Social Drive는 운전 중 좋았던 순간을 안전하게 남기고, 주차 후 코스로 완성해, 다음 운전자가 차량 내비게이션으로 바로 이어 달리게 합니다.

### 2장 — 드라이브 경험은 세 앱으로 끊겨 있다

화면:

- SNS에서 발견
- 지도에서 장소 재검색
- 차량 내비게이션에 다시 입력
- 운전 중 기록 시 주의 분산 위험

발표 문장:

> 좋은 길을 발견하는 곳과 실제로 달리는 곳이 분리되어 있습니다. 반대로 운전 중 그 순간을 남기려는 행동은 안전과 충돌합니다.

평가 연결: 차량특화성·창의성. [공식 심사 기준](https://appdevchallenge.auto-hmg.io/guide/judging-awards)

### 3장 — 기록, 게시, 따라가기의 세 여정

화면:

1. Driving: 큰 버튼 한 번으로 하이라이트
2. Parked: 제목·설명·공개 범위를 확인하고 게시
3. Navi: 목적지·경유지를 전달하고 안내 시작

각 카드에는 상태 배지를 넣는다.

- Compose/Room/Worker: 현재 `코드 존재(미통합)`
- preset 경로: `데모 데이터`
- Vehicle/NaviHelper: 현재 1차 반려 후 수정 `98a2978`의 `코드 존재(미통합)`, 독립 재검토 진행 중; `APPROVE`와 AVD 실기 성공 후에만 `실제 SDK`

### 4장 — 모바일이 아니라 차량이어야 완성된다

화면:

- Vehicle adapter 구현 목표 → 안전 UI 전환
- NaviHelper adapter 구현 목표 → 실제 주행으로 전환
- Room/WorkManager → 네트워크 단절 복구
- 여섯 상태 등급 truth table

발표 문장:

> 구현 목표는 Vehicle 상태로 사용자가 입력할 수 있는 순간을 결정하고 NaviHelper로 콘텐츠를 실제 주행에 연결하는 것입니다. 두 adapter는 1차 반려 후 수정 `98a2978`에 있지만 독립 재검토 진행 중, main 미병합·Gradle/AVD 검증 전이며, Coming Soon SDK도 실제 기능으로 주장하지 않습니다.

평가 연결: 기술 활용성. [공식 개발 환경](https://appdevchallenge.auto-hmg.io/guide/development), [공식 SDK 상태](https://document.pleos.ai/en/docs/pleos-only/create-my-project)

### 5장 — 안전과 개인정보를 기능 구조로 강제했다

화면:

```text
Driving / Unknown
→ 기록 상태와 하이라이트만
→ session·highlightCount·draft·queue 로컬 보존

Parked
→ 편집·동의·게시
→ Worker가 motion 재검증
→ 향후 동의·공개 범위 구현 후 선택한 정보만 서버 전송
```

발표 포인트:

- Unknown도 fail-closed
- 게시 성공 전 원본 유지
- 토큰·presigned URL 비영속
- 좌표·주소·자격증명 비로그

### 6장 — 작동하는 기반과 반복 사용 구조

왼쪽, 현재 코드 증거:

- Android 별도 작업 브랜치: recording session, `highlightCount`, draft, pending queue, preset reference와 안전 gate 코드
- 백엔드 별도 작업 브랜치: Cognito JWT, DynamoDB, private S3 계약
- 테스트 소스: 안전 정책, Room migration, 오프라인 복구, API security
- PLEOS adapter 코드 증거: 최초 `6475287` 1차 반려 후 race/lifecycle/permission 수정 `98a2978`; 독립 재검토 진행 중
- 아직 없는 증거: 실제 경로점·위치 수집, 위치 동의·철회 UI, main 병합, Gradle 성공, AVD 실기 결과, 앱-AWS E2E, release APK
- root `readme.md`: 공식 네 항목의 문서 구조 완료. 빌드·AVD·APK 실증 결과는 아직 갱신 필요

오른쪽, retention loop:

```text
탐색 → 따라가기 → 하이라이트 → 게시 → 콘텐츠 증가 → 재탐색
```

지표:

- activation
- 코스당 Navi 실행
- 게시 완료율
- W1/W4 retention
- 중단된 게시의 복구율

MAU는 `[A × B% × C%]` 산식과 각 근거 출처를 표시한다.

### 7장 — 11월 15일 제출, 12월 실제 차량 경험 증명

화면:

- 11/15 필수: 기획서
- 11/15 선택 가산점 목표: GitHub, 소스, release APK, 시연 영상
- 11/23: 1차 결과
- 12/3~4: 실제 동작 앱 PT
- 남은 P0: Pleos 실기, Android-AWS E2E, release APK, 제출 검수

마무리 문장:

> 좋은 길은 검색 결과가 아니라, 먼저 달린 운전자의 경험에서 시작됩니다.

공식 일정과 제출 조건: [참가자 모집 공고](https://appdevchallenge.auto-hmg.io/notice/c0a1a000-2026-4923-8000-000000000001), [공식 제출 안내](https://appdevchallenge.auto-hmg.io/guide/submission)

## 3. 내부 권장 90~150초 시연 영상 shot list

90~150초는 공식 제출 제한이 아니라 내부 편집 권장 길이다. 권장 본편은 약 125초이며 선택 장면을 포함해도 내부 목표상 150초를 넘기지 않는다.

| 시간 | 장면 | 화면 배지 | 내레이션/증거 |
|---|---|---|---|
| 0–7초 | 로고, 앱명, 차량 화면 | 없음 | “한 번의 터치가 다음 사람의 드라이브가 됩니다.” |
| 7–17초 | SNS→지도→내비 재입력 Before | 문제 정의 | “좋은 코스를 발견해도 장소를 다시 찾아야 합니다.” |
| 17–29초 | 피드와 드라이브 상세 | 현재 `코드 존재(미통합)` 또는 `데모 데이터` | 코스, 하이라이트, preset 목적지 확인 |
| 29–43초 | `이 코스 따라가기`와 Navigation 앱 | 현재 `코드 존재(미통합)`; 검증 성공 시 `실제 SDK` | 1차 반려 후 수정 `98a2978`, 독립 재검토 진행 중. `APPROVE`·Gradle·실제 NaviHelper callback 증거가 없으면 데모로 대체 |
| 43–57초 | Virtual Console P→D, 앱 안전 전환 | 현재 `코드 존재(미통합)`; 검증 성공 시 `실제 SDK` | 1차 반려 후 수정 `98a2978`, 독립 재검토 진행 중. `APPROVE`·Gradle·Vehicle AVD 증거가 없으면 데모로 대체 |
| 57–70초 | 기록 화면에서 `방금 좋았어` 한 번 터치 | 현재 `코드 존재(미통합)` 또는 `데모 데이터` | 주행 중 긴 입력이 없음을 보여 줌 |
| 70–80초 | 앱 종료·재실행 후 세션/초안 복원 | 검증 후 `앱 구현` | session·draft·queue persistence 증거. 실제 경로점 저장으로 표현 금지 |
| 80–91초 | D 또는 Unknown에서 게시 진입 거부 | 검증 후 `앱 구현` | fail-closed 안전 정책 |
| 91–107초 | P에서 게시물 검토·게시 | 검증 후 `앱 구현` | 주행/주차 역할 분리 |
| 107–117초 | 네트워크 중단→Pending→복구 | 검증 후 `앱 구현` | WorkManager와 draft 보존 |
| 117–125초 | 게시물이 피드에 등장 | 통합 성공 수준 표기 | retention loop 마무리 |
| 125–132초, 선택 | 기술 truth table | 여섯 등급 모두 | 공식 상태 등급 구분 |
| 132–140초, 선택 | GitHub·APK·테스트 카드 | 해당 기능의 공식 상태 등급 | commit, APK checksum, AVD와 실행 결과 |

### 촬영 규칙

- `실제 SDK` 배지는 Pleos AVD 실기와 callback이 확인된 장면에만 사용한다.
- NaviHelper 후속 검토·Gradle·AVD 증거가 없으면 29–43초를 `코드 존재(미통합)` 설명 카드로 대체하거나 fake 화면에 `데모 데이터`를 표시한다.
- Vehicle adapter 후속 검토·Gradle·AVD 증거가 없으면 P/D 전환을 실제 SDK로 연출하지 않는다.
- FusedLocation/STT/TTS/Vehicle Control 화면은 본편 실제 흐름에 넣지 않는다.
- 민감한 계정, 토큰, Client Secret, 정확한 개인 위치, 콘솔 자격증명을 촬영하지 않는다.
- 영상 마지막에 commit SHA, APK SHA-256, AVD 버전, 실행한 테스트 결과를 표시한다.

## 4. 11월 15일 역산 일정

### 11월 1–3일 — Pleos 실기 기반

- Vehicle 2.3.2 / NaviHelper 3.0.5 flavor 분리
- Pleos Emulator 2.1.0 API34 설치
- initialize, capability, permission, callback, release 증거 확보
- P/D/R/N, Unknown, Navi route callback 녹화

### 11월 4–6일 — 통합

- 독립 검토를 통과한 Android·백엔드 브랜치를 main 통합 후보로 준비
- Android 인증·feed·drive·upload API 연결
- private S3와 presigned upload E2E

### 11월 7–9일 — 안전·복구 검증

- 오프라인, 재시작, 주행 전환, 권한 거부 테스트
- Vehicle/Navi service unavailable 테스트
- day/night, 화면비, glanceability 확인

### 11월 10일 — 기능 동결

- 여섯 상태 등급과 기능별 문구 동결
- P0가 아닌 신규 기능 중단
- 미완료 기능을 실제 제출 범위에서 제외

### 11월 11–12일 — release와 영상

- release signing과 APK 설치·재실행
- 시연 영상 촬영·편집
- README 소개·실행·환경·APK 위치 작성

### 11월 13일 — 접수양식

- 공식 양식 최종 작성
- 개인정보 동의와 팀원 서명
- `apk/[팀명]_SocialDrive_release.apk` 배치

### 11월 14일 — 제출 리허설

- 깨끗한 Pleos AVD에서 APK 설치
- 비공개 GitHub 심사용 계정 접근 확인
- 링크·영상·APK checksum·README 검수
- 제출 메일 초안과 전체 첨부파일 확인

### 11월 15일 — 제출

- 필수 접수양식(기획서)을 내부 마감 `[HH:MM KST]`까지 제출
- 준비된 경우에만 GitHub·소스·APK·영상을 선택 가산점 자료로 함께 제출
- 팀원 CC와 서명 재확인
- 발신 메일과 최종 첨부파일을 보관

### 11월 16일–12월 2일 — PT 안정화

- 11/23 결과 전에도 치명적 결함과 실기 증거 보완
- 합격 시 실제 앱 중심으로 PT 자료 갱신
- 네트워크 장애용 녹화 backup과 local demo backup 준비
- 내부 권장 5분/7분 발표 버전 리허설. 공식 발표 시간이 안내되면 교체
- 예상 질문 답변: 모바일 차별성, 안전 강제, 위치정보, SDK 진실표, MAU 근거

## 5. Go / No-Go 게이트

### 11월 10일 Feature Freeze

Go:

- [ ] 핵심 3개 여정이 앱에서 끊김 없이 연결됨
- [ ] 여섯 상태 등급이 기능별로 확정됨
- [ ] 미완료 SDK 기능 없이도 앱이 안전하게 동작함

No-Go 대응:

- Vehicle/Navi 실기가 불안정하면 해당 장면을 데모로 명시하고 실제 기능 주장 제거
- 핵심과 무관한 기능은 삭제 또는 숨김

### 11월 12일 Release Candidate

Go:

- [ ] release APK가 깨끗한 Pleos AVD에 설치·실행됨
- [ ] 주행·Unknown 상태에서 편집·게시 우회가 없음
- [ ] 오프라인 초안과 게시 재개가 재현됨
- [ ] 비밀·개인정보가 APK, 로그, 영상에 없음

No-Go 대응:

- 앱-AWS E2E가 불안정하면 로컬 read-only feed fallback을 사용하되 데모임을 표시
- 실제 SDK 실패를 fake로 조용히 대체하지 않음

### 11월 14일 Submission Candidate

필수 제출 Go:

- [ ] 공식 접수양식과 전원 서명 완료

선택 가산점 산출물을 함께 낼 경우 추가 Go:

- [ ] GitHub 심사 계정 접근 가능
- [ ] README 필수 항목과 APK 경로 확인
- [ ] 영상 링크를 로그아웃/별도 환경에서 재생 가능
- [ ] APK SHA-256, commit SHA, 테스트 결과 기록
- [ ] 기획서·PT·영상의 기능 상태가 서로 일치

하나라도 실패하면 해당 산출물을 고치거나, 검증되지 않은 선택 제출물을 제외하고 기획서의 범위를 축소한다. 검증되지 않은 기능을 완료로 표시한 채 제출하지 않는다.

## 6. PT 예상 질문

### 모바일 앱과 무엇이 다른가?

완성 목표에서는 Vehicle 상태가 입력 가능 범위를 결정하고 NaviHelper가 콘텐츠를 차량의 실제 경로 안내로 전환한다. 두 adapter는 1차 반려 후 수정 `98a2978`에 있지만 독립 재검토 진행 중, main 미병합·Gradle/AVD 검증 전이다. 이 목표가 실제 검증되면 모바일과 달리 차량 상태 기반 안전 전환부터 차량 Navigation 앱까지 흐름을 완결할 수 있다.

### 주행 중 안전을 어떻게 강제하는가?

별도 Android 작업 브랜치에는 Driving뿐 아니라 Unknown도 막는 정책과 Worker gate 코드가 존재하지만 아직 제출 브랜치에 통합·실행 검증되지 않았다. Vehicle adapter 독립 재검토 통과·main 통합 후 UI 진입 시점과 원격 호출 직전에 실제 motion을 재검증하는 것을 release Go 조건으로 삼는다.

### 위치정보는 언제 서버로 가는가?

현재 실제 위치·경로점 수집과 위치정보 동의·철회 UI는 미구현이며 preset reference만 사용한다. 향후 실제 위치 기능을 구현할 때 명시적 동의·철회와 공개 범위를 먼저 제공하고, 게시를 확정한 데이터만 업로드하는 것을 Go 조건으로 삼는다.

### 실제 SDK와 데모 기능은 무엇인가?

`docs/contest/official-requirements.md`의 truth table과 당일 실기 증거를 기준으로 답한다. AVD callback 증거가 없는 기능은 실제 SDK로 말하지 않는다.

### MAU와 retention의 근거는 무엇인가?

MAU는 공식 도달 가능 활성 차량, 설치 전환, 월간 활성화율의 곱으로 산출하고 각 값의 출처를 제시한다. retention은 코스 탐색·기록·따라가기 중 하나를 수행한 W1/W4 코호트로 측정한다.
