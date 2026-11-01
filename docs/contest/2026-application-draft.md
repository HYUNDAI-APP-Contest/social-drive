# Social Drive 2026 공모전 기획서 초안

> 개인정보와 검증되지 않은 수치는 placeholder로 유지한다. 접수양식의 실제 항목 순서에 맞춰 옮기되, 기술 사실과 기능 상태는 바꾸지 않는다.

## 1. 참가 정보

| 항목 | 입력값 |
|---|---|
| 팀명 | `[팀명]` |
| 대표자 | `[대표자명]` |
| 참가 부문 | `[대학생 / 일반·스타트업]` |
| 팀원 | `[팀원 성명/소속]` |
| 연락처 | `[제출본에서만 입력]` |
| 이메일 | `[제출본에서만 입력]` |
| GitHub | `[심사용 저장소 URL]` |
| 시연 영상 | `[영상 URL]` |
| APK | `apk/[팀명]_SocialDrive_release.apk` |

## 2. 앱명과 한 줄 소개

### 앱명

**Social Drive | 소셜 드라이브**

### 한 줄 소개

**운전 중 좋았던 순간을 한 번의 터치로 남기고, 주차 후 안전하게 공유하며, 다른 운전자의 코스를 차량 내비게이션으로 바로 이어 달리는 로컬 퍼스트 드라이브 커뮤니티.**

## 3. 문제와 타깃

### 문제

좋은 드라이브 코스를 발견하는 곳과 실제로 주행하는 곳이 분리되어 있다. SNS에서 사진이나 후기를 발견해도 지도에서 장소를 다시 찾고, 차량 내비게이션에 목적지와 경유지를 다시 입력해야 한다. 반대로 운전 중 좋았던 순간을 메모하거나 자세히 기록하려는 행동은 주의 분산과 충돌한다.

Social Drive는 다음 경험을 제출 목표로 한다. PLEOS·cloud·route-consent 경계를 포함한 Android HEAD `b1c33a2`는 Sentinel 독립 검토 `APPROVE`, core Kotlin 51/51, npm 25/25, Room v4·`수동 SQLite predicate/migration PASS` 상태다. 이는 Room instrumentation 결과가 아니다. P0-1/P0-2는 정적·core 범위에서 완료됐지만 main 미병합이며 actual cloud·Gradle·instrumentation·vendor adapter·APK·Pleos AVD·AWS E2E는 실행되지 않았다. 실제 위치·경로점 수집도 아직 구현되지 않았다.

1. 주행 중에는 기록 시작·종료와 `방금 좋았어` 한 번의 터치만 제공한다.
2. recording session, `highlightCount`, draft, pending queue와 preset reference를 로컬 DB에 보존한다.
3. 제목·설명·공개 범위 확인은 주차 후에만 허용한다.
4. NaviHelper adapter를 main에 통합하고 Gradle/AVD 검증을 마친 뒤 공개 코스의 목적지와 경유지를 차량 Navigation 앱으로 전달한다.

위치 동의·철회와 route source 안전 경계는 `코드 존재(미통합)`이지만 실제 위치 샘플·경로점 수집은 미구현이다. preset 경로는 실제 위치가 아니라 `데모 데이터`다. 게시물 삭제도 주차 상태의 local `DELETE_PENDING`까지만 존재하며 network `DELETE`·retry는 없다.

### 핵심 타깃

- 주말 드라이브와 근교 여행을 즐기는 Pleos Connect 운전자
- 좋은 도로·전망 구간을 발견하고 공유하려는 자동차 커뮤니티 사용자
- 검증된 코스를 차량에서 바로 따라가고 싶은 신규 운전자·여행자

타깃 규모는 `[공식 또는 신뢰 가능한 출처 확보 후 입력]`한다. 근거 없는 차량 수나 시장 규모를 만들지 않는다.

## 4. 목표 Before / After

다음 표는 완성 목표다. 현재 구현 증거는 8절 상태표를 기준으로 한다.

| Before | Social Drive 이후 |
|---|---|
| SNS에서 본 코스를 지도와 차량 내비에 다시 입력 | NaviHelper 검증 완료 후 게시물의 목적지·경유지를 전달 |
| 운전 중 메모·검색으로 주의가 분산될 수 있음 | 큰 버튼 한 번으로 하이라이트만 로컬 기록 |
| 차량 상태와 무관하게 편집 UI가 열릴 수 있음 | Driving과 Unknown에서 편집·게시를 fail-closed 차단 |
| 연결이 끊기면 기록·업로드를 잃을 수 있음 | Room 초안과 WorkManager 큐로 재시작·오프라인 복구 |
| 이동 정보가 수집 즉시 외부로 전송될 수 있음 | consent/withdraw 안전 경계를 먼저 두고, 향후 검증된 위치 source가 생겨도 게시 확정 전 로컬 보존과 선택 업로드 적용 |

## 5. 차량특화성

차량특화성·창의성·기술 활용성·사업성은 공개된 1차 서류심사 기준이다. 아래 문안은 1차 기획서에 맞춘 것이며, 2차 PT 평가 기준은 공식 추후 안내를 확인한다. 이 문안을 PT 구조에도 활용하는 것은 내부 발표 전략이다. [공식 심사 안내](https://appdevchallenge.auto-hmg.io/guide/judging-awards)

Social Drive는 모바일 SNS를 차량 화면으로 옮긴 앱이 아니다.

### 차량 상태가 UX를 결정하는 구조를 구현한다

Vehicle adapter의 목표는 Vehicle State와 공식 주행 제한 상태를 통해 `Parked / Driving / Unknown`을 판정하는 것이다. 서비스 오류, 권한 부족, 오래된 값처럼 안전을 증명할 수 없는 경우는 `Unknown`으로 처리해 편집과 게시를 차단한다. HEAD `b1c33a2`는 Sentinel `APPROVE`와 core Kotlin 51/51을 통과했지만 main 미병합이고 vendor adapter·Gradle·Android instrumentation·Pleos AVD가 미실행이므로 실제 Vehicle 상태 연동으로 표현하지 않는다.

### 콘텐츠가 실제 주행으로 완결된다

완성 목표에서는 사용자가 주차 상태에서 `이 코스 따라가기`를 누르면 NaviHelper에 목적지와 경유지를 전달하여 차량 Navigation 앱의 경로 안내로 이어진다. HEAD `b1c33a2`는 PLEOS `89078a0`을 포함하고 core 검토를 통과했지만 main 미병합이고 vendor adapter·Gradle·AVD callback은 미실행이다.

### 주행과 정차의 역할을 분리한다

- 주행 중: 기록 상태 확인, 기록 종료, 하이라이트 한 번 터치
- 주차 중: 제목·설명·대표 이미지·공개 범위 확인, 게시, 설정

공식 심사는 모바일 대비 차량만의 가치와 주행·정차/차량 기능의 결합을 차량특화성으로 평가한다. [공식 심사 기준](https://appdevchallenge.auto-hmg.io/guide/judging-awards)

## 6. 창의성

Social Drive가 목표로 하는 콘텐츠 단위는 장소 후기가 아니라 **실제로 달린 경로와 그 경로 위의 감정 하이라이트**다. 다만 현재 저장소에는 실제 경로점·위치 샘플 수집 증거가 없으며, 현 단계 화면은 preset reference를 사용한다.

- “이 카페가 좋다”에서 “이 길의 이 구간이 좋았다”로 공유 단위를 바꾼다.
- 순간 표시와 설명 작성을 주행/주차로 분리하여 안전과 표현력을 함께 확보한다.
- NaviHelper main 통합·Gradle/AVD 검증 후 콘텐츠 소비를 실제 주행으로 연결한다.
- 따라 달린 사용자가 새로운 하이라이트와 코스를 만들면서 콘텐츠가 순환한다.

## 7. 3개 핵심 사용자 여정

### 여정 A: 달리며 순간 남기기

1. 주차 상태에서 드라이브 기록을 시작한다.
2. 주행이 시작되면 축약된 기록 화면으로 전환한다.
3. 좋은 구간에서 `방금 좋았어`를 한 번 누른다.
4. recording session과 `highlightCount`, draft, pending queue, preset reference를 Room에 저장한다.
5. 네트워크가 없어도 세션과 초안을 보존한다.

현재 증거: HEAD `b1c33a2`에는 session/draft/queue, preset reference, route source, consent/withdraw와 Room v4가 있다. 실제 경로점·위치 샘플 수집과 Vehicle callback은 없다. preset 경로는 반드시 `데모 데이터`로 표시한다.

### 여정 B: 주차 후 안전하게 게시하기

1. 차량 상태가 `Parked`인지 확인한다.
2. 제목·설명·대표 이미지·공개 범위를 검토한다.
3. 게시 요청을 `PendingUpload`로 저장한다.
4. create/upload/publish 단계마다 주차 상태를 재검증한다.
5. Driving 또는 Unknown이면 원본을 보존하고 네트워크 작업을 중단한다.
6. Parked가 다시 확인되면 같은 idempotency key로 복구한다.

현재 증거: Room/WorkManager 기반 게시 큐와 motion gate 구현·테스트 소스가 별도 Android 작업 브랜치에 있다. 테스트 실행 로그와 Android-AWS E2E는 아직 제출 증거로 확보되지 않았다.

### 여정 C: 다른 운전자의 길 따라가기

1. 피드에서 드라이브를 선택한다.
2. 목적지·경유지·하이라이트를 미리 본다.
3. 주차 상태에서 `이 코스 따라가기`를 누른다.
4. NaviHelper main 통합·Gradle/AVD 검증 후 목적지와 경유지를 전달한다.
5. 실제 callback이 확인된 경우에만 차량 Navigation 앱 안내 시작을 시연한다.

현재 증거: UI와 fake 흐름은 별도 Android 작업 브랜치에 있으므로 `데모 데이터`다. HEAD `b1c33a2`는 PLEOS·cloud·route-consent 코드를 포함하고 Sentinel `APPROVE`지만 `코드 존재(미통합)` 상태다. NaviHelper vendor adapter·Gradle assemble·initialize/request/callback은 실행되지 않았다.

## 8. 기술 활용성과 구현 상태

| 기능 | 제출 목표 | 2026-11-01 현재 사실 | 표기 |
|---|---|---|---|
| Compose 피드·상세·기록·게시 UI | 핵심 흐름 제공 | Android 별도 브랜치에 코드 존재, 제출 브랜치 통합·실행 전 | `코드 존재(미통합)` |
| Room recording/draft/queue | 오프라인/재시작 복구 | session, `highlightCount`, draft, queue, preset reference 코드만 확인 | `코드 존재(미통합)` |
| motion 재검증 Worker | 주행 중 원격 게시 차단 | Android 별도 브랜치에 코드 존재, Pleos motion 연동 전 | `코드 존재(미통합)` |
| route source·동의·철회 | 개인정보 통제와 demo/live 분리 | `b1c33a2`, Sentinel `APPROVE`, Room v4·`수동 SQLite predicate/migration PASS`. 실제 위치 source는 unavailable | `코드 존재(미통합)` |
| 실제 경로점·위치 수집 | 실제 코스 생성 | 확인된 구현 증거 없음 | `미구현` |
| 게시물 삭제 | parked-only 삭제 요청 | local `DELETE_PENDING`까지만 구현; network `DELETE`·retry 없음 | `코드 존재(미통합)` |
| Cognito/DynamoDB/S3 API | 사용자·피드·비공개 업로드 | `b1c33a2`에 backend `ddbd61f` 계약 포함. concrete Cognito/API host·no-redirect 설정 부재로 actual cloud unavailable | `코드 존재(미통합)` |
| Vehicle SDK 2.3.2 | Parked/Driving/Unknown 판정 | `b1c33a2`가 PLEOS `89078a0` 포함, Sentinel `APPROVE`, core 51/51, npm 25/25; vendor adapter·Gradle·instrumentation·APK·AVD 미실행 | `코드 존재(미통합)` |
| NaviHelper 3.0.5 | 목적지·경유지 전달 | `b1c33a2`가 PLEOS `89078a0` 포함, Sentinel `APPROVE`, core 51/51, npm 25/25; vendor adapter·Gradle·instrumentation·APK·AVD 미실행 | `코드 존재(미통합)` |
| preset route/motion | 전체 흐름 시연 보조 | 데모 전용 | `데모 데이터` |
| FusedLocation/STT/TTS/Vehicle Control | 향후 자연스러운 입력·안내 | 공식 Coming Soon | `향후 기능` |

P0-3 로컬 점검에서 JDK17, SDK/API34, build-tools와 Pleos AVD 정의는 확인됐다. 그러나 worker sandbox에서 Gradle native initialization, emulator CPU feature 확인과 DNS/HTTP가 실패했고 AGP·Kotlin Gradle plugin·vendor artifact cache도 없었다. 따라서 build·boot·APK 성공 증거로 사용하지 않는다.

Vehicle State와 NaviHelper는 현재 Ready to Use지만, FusedLocation·STT·TTS·Vehicle Control은 Coming Soon이다. Coming Soon 기능은 이번 MVP의 실제 기능이나 필수 의존성으로 사용하지 않는다. [공식 SDK 상태](https://document.pleos.ai/en/docs/pleos-only/create-my-project)

## 9. 안전·개인정보 보호

### 현재 확인된 코드 증거

- 별도 Android 작업 브랜치에 recording session, `highlightCount`, draft, pending queue와 preset reference 코드가 존재한다.
- Android HEAD `b1c33a2`는 motion gate, cloud contract, route source, consent/withdraw, Room v4와 local `DELETE_PENDING`을 포함한다. Sentinel `APPROVE`, core 51/51, npm 25/25, `수동 SQLite predicate/migration PASS`지만 Room instrumentation·Gradle·AVD·AWS E2E는 미실행이다.
- 실제 경로점·위치 샘플 수집은 미구현이며 published delete의 network `DELETE`·retry도 없다.

### 구현·검증해야 할 요구사항

- `Driving`뿐 아니라 상태를 확인할 수 없는 `Unknown`도 편집·게시 차단
- Worker가 실제 원격 호출 직전에 motion을 다시 확인
- 주행 중 장문 입력, 설정 변경, 게시, 동영상·자동 스크롤 금지
- 실제 위치 수집 전 `b1c33a2`의 동의·철회·공개 범위 경계를 통합·runtime 검증하고 공식 source/permission을 별도 승인
- 향후 수집하는 실제 위치·경로점은 게시 확정 전까지 Room에 로컬 보존
- 향후 사용자가 게시하기로 선택한 필드만 서버 전송
- 게시 성공 전 로컬 원본 삭제 금지
- S3 객체는 비공개이며 짧게 만료되는 presigned URL 사용
- 인증 토큰과 presigned URL을 Room이나 WorkManager input에 저장하지 않음
- 좌표·주소·JWT·비밀키를 로그에 기록하지 않음
- APK에 AWS 비밀키, 관리자 키, Client Secret을 포함하지 않음
- 위치정보 동의·철회: `코드 존재(미통합)` — actual source unavailable을 유지하고 Gradle/instrumentation 검증 후에만 `앱 구현`으로 승격

## 10. 사업성

### MAU 산식

공식 활성 차량 수와 실제 전환율을 확보하기 전에는 임의 숫자를 넣지 않는다.

```text
예상 MAU
= [Pleos App Market 월간 도달 가능 활성 차량 A]
× [스토어 노출→설치 전환율 B%]
× [설치 후 월 1회 이상 코스 탐색·기록 비율 C%]
```

운영 이후에는 다음처럼 측정한다.

```text
월 MAU = 월 신규 활성 사용자 + 전월 사용자 중 월간 재방문 사용자
W4 Retention = 가입 4주차에 기록·탐색·따라가기 중 하나를 수행한 사용자 / 해당 코호트
```

입력할 근거:

- A: `[Pleos 활성 차량 또는 App Market 도달 규모 출처]`
- B: `[자동차/앱스토어 설치 전환 벤치마크 출처]`
- C: `[클로즈드 베타 또는 사용자 테스트 결과]`

### 목표 Retention loop

```text
좋은 코스 발견
→ NaviHelper 실제 검증 후 따라가기
→ 주행 중 하이라이트 생성
→ 주차 후 새 코스 게시
→ 지역별 콘텐츠 증가
→ 다음 코스를 탐색할 이유 증가
```

핵심 지표:

- 가입 7일 내 첫 `따라가기` 비율
- 첫 주행 후 게시 완료율
- 코스당 NaviHelper 실행 수
- W1/W4 재방문율
- 월간 소비자 대비 제작자 비율
- 게시 중단 후 복구 성공률

### 확장 방향

- 지역 관광·드라이브 코스 큐레이션
- 전기차 충전·휴게 지점을 포함한 테마 경로
- 자동차 동호회·브랜드별 공개/비공개 코스
- 해당 SDK가 실제 Ready to Use로 전환된 뒤 음성 하이라이트·안내 검토

공식 사업성 평가는 타깃, 예상 MAU, retention과 확장 가능성을 본다. [공식 심사 기준](https://appdevchallenge.auto-hmg.io/guide/judging-awards)

## 11. 제출 산출물

### 11월 15일 필수 제출

- [ ] 개인정보 동의가 포함된 공식 접수양식

### 선택 제출·가산점 목표

- [ ] GitHub 심사용 저장소와 접근 권한
- [ ] `apk/[팀명]_SocialDrive_release.apk`
- [ ] 설치·재실행 가능한 release APK 검증 기록
- [ ] 내부 권장 길이 90~150초의 시연 영상
- [ ] 여섯 상태 등급을 적용한 기능 truth table
- [x] root README의 소개·현재 실행 상태·환경·APK 목표 위치 문서화
- [ ] 실제 빌드 후 README 명령·산출물 경로·APK 링크 갱신
- [ ] 실행한 테스트 명령, commit SHA, 결과 로그

현재 root `readme.md`는 공식 네 항목의 문서 구조를 갖췄고, Android/main 미병합·빌드/AVD 미검증·APK 생성 전 상태와 목표 경로를 명시한다. 선택 제출 전 실제 빌드 명령과 APK 경로를 검증 결과로 갱신해야 한다.

기획서는 11월 15일 필수 제출이며 GitHub·소스·APK·영상은 선택 가산점이다. 90~150초는 공식 제한이 아니라 이 저장소의 내부 영상 권장 길이다. 2차 PT 대상자는 실제 동작 앱을 준비해야 한다. [공식 제출 안내](https://appdevchallenge.auto-hmg.io/guide/submission)
