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

Social Drive는 다음 경험을 제출 목표로 한다. PR #2 병합 커밋 `dcffaad`를 포함한 현재 통합 소스에는 PLEOS·cloud·route-consent, Room conflict review와 published-delete lifecycle이 있다. PLEOS Connect Android 14(user 10)에서 Vehicle PARK와 NaviHelper `onRouteStarted` 실제 callback을 확인했다. actual cloud·AWS E2E·제출용 signed release·Vehicle D/R/N·실차 검증은 남아 있다.

1. 주행 중에는 기록 시작·종료와 `방금 좋았어` 한 번의 터치만 제공한다.
2. recording session, `highlightCount`, draft, pending queue와 preset reference를 로컬 DB에 보존한다.
3. 제목·설명·공개 범위 확인은 주차 후에만 허용한다.
4. 통합된 NaviHelper adapter는 실제 route callback이 확인된 뒤에만 공개 코스의 목적지와 경유지를 차량 Navigation 앱으로 전달한다.

위치 수집은 별도 고지와 정책 버전 동의, PLEOS `FUSED_LOCATION` 권한을 모두 통과한 기록 세션에서만 동작한다. AVD에서 실제 SDK callback을 받아 원본 위·경도를 메모리에서 정규화하고 거리와 비정밀 경로만 Room에 보존하는 흐름을 검증했다. preset 경로는 계속 `데모 데이터`로 구분한다. 실제 차량 GNSS 품질과 장시간 주행은 아직 미검증이다. 게시물 삭제는 Room tombstone, network `DELETE`, bounded retry 계약까지 구현·계측됐지만 actual cloud 설정이 없어 demo transport 증거다.

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

Vehicle adapter는 Vehicle State와 공식 주행 제한 상태를 통해 `Parked / Driving / Unknown`을 판정한다. 서비스 오류, 권한 부족, 오래된 값처럼 안전을 증명할 수 없는 경우는 `Unknown`으로 처리해 편집과 게시를 차단한다. PLEOS AVD에서 실제 `GEAR_PARK`가 앱의 `Parked`까지 도달한 계측 테스트가 통과했다. D/R/N과 실차는 아직 검증하지 않았으므로 PARK 외 상태까지 검증한 것으로 표현하지 않는다.

### 콘텐츠가 실제 주행으로 완결된다

완성 목표에서는 사용자가 주차 상태에서 `이 코스 따라가기`를 누르면 NaviHelper에 목적지와 경유지를 전달하여 차량 Navigation 앱의 경로 안내로 이어진다. HEAD `4f2b559`의 NaviHelper coordinator와 lifecycle은 빌드·core 검증됐지만 실제 route/event callback 실기 증거는 미완료다.

### 주행과 정차의 역할을 분리한다

- 주행 중: 기록 상태 확인, 기록 종료, 하이라이트 한 번 터치
- 주차 중: 제목·설명·대표 이미지·공개 범위 확인, 게시, 설정

공식 심사는 모바일 대비 차량만의 가치와 주행·정차/차량 기능의 결합을 차량특화성으로 평가한다. [공식 심사 기준](https://appdevchallenge.auto-hmg.io/guide/judging-awards)

## 6. 창의성

Social Drive가 목표로 하는 콘텐츠 단위는 장소 후기가 아니라 **실제로 달린 경로와 그 경로 위의 감정 하이라이트**다. 다만 현재 저장소에는 실제 경로점·위치 샘플 수집 증거가 없으며, 현 단계 화면은 preset reference를 사용한다.

- “이 카페가 좋다”에서 “이 길의 이 구간이 좋았다”로 공유 단위를 바꾼다.
- 순간 표시와 설명 작성을 주행/주차로 분리하여 안전과 표현력을 함께 확보한다.
- 통합 소스의 build/AVD를 재검증하고, 실제 NaviHelper callback이 확인되면 콘텐츠 소비를 실제 주행으로 연결한다.
- 따라 달린 사용자가 새로운 하이라이트와 코스를 만들면서 콘텐츠가 순환한다.

## 7. 3개 핵심 사용자 여정

### 여정 A: 달리며 순간 남기기

1. 주차 상태에서 드라이브 기록을 시작한다.
2. 주행이 시작되면 축약된 기록 화면으로 전환한다.
3. 좋은 구간에서 `방금 좋았어`를 한 번 누른다.
4. recording session과 `highlightCount`, draft, pending queue, preset reference를 Room에 저장한다.
5. 네트워크가 없어도 세션과 초안을 보존한다.

현재 증거: HEAD `4f2b559`에는 session/draft/queue, route source, consent/withdraw와 Room v6 코드가 있고 persistence·migration 계측이 통과했다. 실제 경로점·위치 샘플 수집과 Vehicle callback은 없다. preset 경로는 반드시 `데모 데이터`로 표시한다.

### 여정 B: 주차 후 안전하게 게시하기

1. 차량 상태가 `Parked`인지 확인한다.
2. 제목·설명·대표 이미지·공개 범위를 검토한다.
3. 게시 요청을 `PendingUpload`로 저장한다.
4. create/upload/publish 단계마다 주차 상태를 재검증한다.
5. Driving 또는 Unknown이면 원본을 보존하고 네트워크 작업을 중단한다.
6. Parked가 다시 확인되면 같은 idempotency key로 복구한다.

현재 증거: HEAD `4f2b559`의 별도 Android 작업 브랜치에 Room/WorkManager 게시 큐, motion gate와 409 durable conflict review 코드가 있다. 409 뒤에는 queue를 `FAILED/VERSION_CONFLICT`로 보존하고 일반 worker claim을 막으며, 사용자의 명시적 최신 버전 확인·adopt 뒤에만 publish-only replay를 큐에 넣는다. Kotlin 74/74, static 29/29과 Room/WorkManager 계측은 통과했지만 actual Android-AWS E2E는 미실행이다.

### 여정 C: 다른 운전자의 길 따라가기

1. 피드에서 드라이브를 선택한다.
2. 목적지·경유지·하이라이트를 미리 본다.
3. 주차 상태에서 `이 코스 따라가기`를 누른다.
4. 통합 소스의 build/AVD를 재검증하고, 실제 NaviHelper callback이 확인되면 목적지와 경유지를 전달한다.
5. 실제 callback이 확인된 경우에만 차량 Navigation 앱 안내 시작을 시연한다.

현재 증거: UI와 fake 흐름은 PR #2 통합 소스의 `앱 구현`이며 preset 위치는 `데모 데이터`다. 기존 Gradle assemble·PLEOS UI 계측은 성공했지만 실제 NaviHelper route callback은 실행되지 않았다.

## 8. 기술 활용성과 구현 상태

| 기능 | 제출 목표 | 2026-11-02 현재 사실 | 표기 |
|---|---|---|---|
| Compose 피드·상세·기록·게시 UI | 핵심 흐름 제공 | PR #2 통합, 기존 PLEOS UI 8/8 통과 | `앱 구현` |
| Room recording/draft/queue | 오프라인/재시작 복구 | PR #2 통합, persistence·migration·WorkManager 포함 비UI 43/43 통과 | `앱 구현` |
| motion 재검증 Worker | 주행 중 원격 게시 차단 | PR #2 통합, WorkManager 계약 계측 통과. 실제 Vehicle motion callback은 미검증 | `앱 구현` |
| route source·동의·철회 | 개인정보 통제와 demo/live 분리 | 정책 버전 동의·철회·권한 재확인과 미게시 경로 삭제를 AVD에서 검증 | `앱 구현` |
| 실제 경로점·위치 수집 | 실제 코스 생성 | PLEOS FusedLocation callback→메모리 정규화→비정밀 Room 저장을 AVD에서 검증. 실차 GNSS는 미검증 | `실제 SDK` |
| 게시물 삭제 | parked-only 삭제 요청 | PR #2 통합. Room v6 tombstone·network `DELETE`·bounded retry 계약을 demo transport에서 계측. actual cloud 미검증 | `앱 구현` |
| Cognito/DynamoDB/S3 API | 사용자·피드·비공개 업로드 | PR #2 통합. backend `ddbd61f` 계약 기반 코드 존재. concrete cloud 설정 부재로 actual cloud unavailable | `앱 구현` |
| 409 conflict review | stale publish의 명시적 사용자 해결 | PR #2 통합. durable FAILED, explicit fetch/adopt, publish-only replay. 일반 worker 자동 claim·GET·overwrite 금지. Room 계측 통과 | `앱 구현` |
| Vehicle SDK 2.3.2 | Parked/Driving/Unknown 판정 | PLEOS AVD PARK callback 통과. D/R/N·실차 미검증 | `실제 SDK (PARK 한정)` |
| NaviHelper 3.0.5 | 목적지·경유지 전달 | PR #2 통합. lifecycle/coordinator build·core 검증, 실제 route callback 미검증 | `앱 구현` |
| PLEOS system Back | 기록 중 종료 확인 | Activity dispatcher 이후 dialog 계약은 검증. TaskView의 실제 system Back 전달은 `UNRESOLVED` | `미구현` |
| preset route/motion | 전체 흐름 시연 보조 | 데모 전용 | `데모 데이터` |
| FusedLocation/STT/TTS | 위치·음성 입력·안내 | pinned PLEOS SDK로 AVD 권한·초기화·location callback·STT timeout/cancel 검증. 실차 품질은 미검증 | `실제 SDK` |
| Vehicle Control | 차량 제어 | 안전 정책상 사용하지 않음 | `향후 기능` |

역사적 commit `88b4938`의 P0-3A bootstrap/doctor는 offline에서 네트워크 요청 0건으로 fail-fast한다. project-required wrapper 8.9 cache와 exact Android platform 35가 없고 `services.gradle.org` DNS가 막혀 tasks/variants/assemble은 시작되지 않았다. 최신 앱 HEAD나 APK·AVD·vendor compile 성공 증거로 사용하지 않는다.

Vehicle Control은 이번 MVP에서 사용하지 않는다. FusedLocation·STT·TTS는 저장소에 고정된 PLEOS SDK와 AVD 실행 증거 범위에서만 실제 SDK로 표현하며, production 이미지 승인과 실차 품질을 주장하지 않는다. [실행 증거](../pleos-runtime-evidence-2026-11-07.md)

## 9. 안전·개인정보 보호

### 현재 확인된 코드 증거

- PR #2 통합 Android 소스에 recording session, `highlightCount`, draft, pending queue와 preset reference 코드가 존재한다.
- 병합 커밋 `dcffaad`를 포함한 소스에는 motion gate, cloud, consent/withdraw, Room v6 conflict review와 published-delete lifecycle이 있다. 기존 PLEOS instrumentation 51/51, Kotlin 74/74, static 29/29, Gradle build/lint 증거가 있으며 actual cloud·AWS E2E·signed release·실 SDK callback은 미완료다.
- 실제 경로점·위치 샘플 수집은 PLEOS AVD에서 검증됐으며 원본 좌표는 저장·업로드하지 않는다. 실차 GNSS와 published delete actual cloud는 검증되지 않았다.

### 구현·검증해야 할 요구사항

- `Driving`뿐 아니라 상태를 확인할 수 없는 `Unknown`도 편집·게시 차단
- Worker가 실제 원격 호출 직전에 motion을 다시 확인
- 주행 중 장문 입력, 설정 변경, 게시, 동영상·자동 스크롤 금지
- 실제 위치 수집 전 통합 소스의 동의·철회·공개 범위 경계를 유지하고 공식 source/permission을 별도 승인
- 향후 수집하는 실제 위치·경로점은 게시 확정 전까지 Room에 로컬 보존
- 향후 사용자가 게시하기로 선택한 필드만 서버 전송
- 게시 성공 전 로컬 원본 삭제 금지
- S3 객체는 비공개이며 짧게 만료되는 presigned URL 사용
- 인증 토큰과 presigned URL을 Room이나 WorkManager input에 저장하지 않음
- 좌표·주소·JWT·비밀키를 로그에 기록하지 않음
- APK에 AWS 비밀키, 관리자 키, Client Secret을 포함하지 않음
- 위치정보 동의·철회: `앱 구현` — actual source unavailable을 유지하며 실제 수집은 별도 승인 후에만 승격

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
- [ ] 제출용 signed release 생성 후 README에 source SHA·산출물 경로·APK 링크·SHA-256 갱신
- [ ] 실행한 테스트 명령, commit SHA, 결과 로그

현재 root `readme.md`는 공식 네 항목, PR #2 통합 상태와 기존 build/PLEOS AVD 증거를 갖췄고, 제출용 signed APK 미완료와 목표 경로를 명시한다. 선택 제출 전 APK 서명·clean install/relaunch·SHA-256과 GitHub 경로를 추가해야 한다.

기획서는 11월 15일 필수 제출이며 GitHub·소스·APK·영상은 선택 가산점이다. 90~150초는 공식 제한이 아니라 이 저장소의 내부 영상 권장 길이다. 2차 PT 대상자는 실제 동작 앱을 준비해야 한다. [공식 제출 안내](https://appdevchallenge.auto-hmg.io/guide/submission)
