# Social Drive 제출 전 P0 안전 백로그

기준일: 2026-11-02
목표: 2026-11-15 1차 제출과 12월 PT를 위해, 남은 작업을 최대 3개 GitHub Issue로 제한한다.

> 이 문서는 2026-11-02 의사결정 기록이다. 이후 FusedLocation·TTS·STT가 통합되고 PLEOS AVD에서 실행 검증됐다. 현재 상태 판단에는 [공식 요구사항 truth table](official-requirements.md)과 [2026-11-07 실행 증거](../pleos-runtime-evidence-2026-11-07.md)를 우선한다.

## 1. 결론

우선순위는 다음과 같다.

1. **P0-1 Android↔Cognito/API/S3 로컬 계약 통합 — 완료(정적·core 승인)**
2. **P0-2 route source·위치 동의 안전 게이트 — 완료(정적·core 승인)**
3. **P0-3 release APK·AVD·서명·제출 증거 — build/instrumentation 완료, 제출 release 미완료**

PR #2 병합 커밋 `dcffaad`를 포함한 현재 통합 소스에는 PLEOS·cloud·route-consent, Room v6 conflict review와 published-delete lifecycle이 있다. 기존 PLEOS UI 8/8 + 비UI 43/43, Kotlin 74/74, static 29/29, Gradle clean/unit/lint와 build 증거가 있다. Issue #28은 production NaviHelper adapter의 공개 목적지 1건을 API 34 emulator application boundary에서 검증했다. actual cloud, 실제 Vehicle callback, NaviHelper UI 전체 여정·경유지 실행·물리 차량, 제출용 signed release와 physical PLEOS Back은 미완료다.

공식적으로 11월 15일에는 기획서가 필수이며 GitHub·APK·영상은 선택 가산점이다. 그러나 2차 PT 대상자는 실제 동작 앱을 완성해야 한다. [공식 제출 안내](https://appdevchallenge.auto-hmg.io/guide/submission), [공식 심사 안내](https://appdevchallenge.auto-hmg.io/guide/judging-awards)

## 2. 증거와 미확인 사항

| 영역 | 확인된 증거 | 아직 증명되지 않은 것 |
|---|---|---|
| Android 통합 소스 | PR #2 병합 커밋 `dcffaad` 포함; 기존 PLEOS 51/51, Kotlin 74/74, static 29/29, Gradle 179 tasks 증거; Issue #28 Navi 공개 목적지 1건의 app-boundary terminal callback | signed release, Vehicle P/D/R/N callback, Navi UI 전체 여정·경유지·물리 차량, physical Back, AWS E2E |
| Android local/consent | session/draft/queue, route source, consent/withdraw, Room v6 delete tombstone와 bounded retry 계측 | 실제 위치 샘플·route point 수집, actual cloud delete |
| Cloud contract | backend `ddbd61fde381d09bc4605c758126ebf6ba0a5ea5` 계약이 `b1c33a2`에 포함 | concrete Cognito/API host와 no-redirect HTTP adapter 설정 부재로 actual cloud unavailable; AWS E2E 미실행 |
| 현재 제출 worktree의 `infra/` | Cognito, HTTP API, DynamoDB, private S3 scaffold와 `/users/me`, `/feed` skeleton 확인 | 최신 backend branch의 전체 drive/publish/upload 계약을 대표하지 않음 |
| SDK 가용성 | Vehicle State와 NaviHelper는 Ready to Use; Pleos flavor build/AVD safety suite 성공; Navi 공개 목적지 1건의 app-boundary terminal callback 검증 | 실제 Vehicle P/D/R/N callback, Navi UI 전체 여정·경유지 실행·물리 차량 |
| 위치 SDK | FusedLocation은 공식 Coming Soon | 실제 위치 수집을 구현·시연할 근거 |
| 409 conflict review | `4f2b559`: durable FAILED, explicit fetch/adopt, publish-only replay; Room/WorkManager 계측 통과 | actual cloud 미실행 |
| P0-3A bootstrap/doctor | 역사적 `88b4938`: offline zero-network fail-fast | 현재 차단은 해소됨; 과거 기록은 회귀 진단용 |
| 제출 산출물 | truth table, 기획서/PT 초안, 통합 Android 소스, 기존 build APK·PLEOS 실행 증거 | signed release·hash·clean install, 영상, Android-AWS E2E |
| PLEOS system Back | MainActivity dispatcher 이후 stop dialog 계약 자동 검증 | TaskView의 physical/synthetic system Back 전달은 `UNRESOLVED` |

FusedLocation, TTS, STT, Vehicle Control은 현재 Not Available Yet/Coming Soon이고 Vehicle State와 NaviHelper만 Ready to Use다. [공식 SDK 상태](https://document.pleos.ai/en/docs/pleos-only/create-my-project)

### 승인된 P0-1/P0-2 pin과 runtime 경계

아래 pin과 결과는 PR #2 병합 전 검토 이력이며, 현재 worktree에는 그 결과가 `dcffaad`를 통해 통합돼 있다. 이 역사적 이력 자체는 실제 SDK callback·actual cloud·제출 release 성공을 뜻하지 않는다. NaviHelper의 제한적 실제 SDK 근거는 별도 Issue #28 exact smoke다.

```text
PLEOS_BASE=89078a09dc29b0ab8346d4909bb87347f5a8efbb
BACKEND_HEAD=ddbd61fde381d09bc4605c758126ebf6ba0a5ea5
ANDROID_INTEGRATED_HEAD=0d315763353a54c65352fffab2d6cb7222ec4a83
ROUTE_CONSENT_HEAD=b1c33a22b18c62806ee89337ef04635c53dd302a
RUNTIME_UNBLOCK_HEAD=88b493860ddc18e2131af723463fe68e43cf515f
CURRENT_ANDROID_HEAD=4f2b559116fd98ee3fe05e05fc6431b59888d0d6
BACKEND_CONTRACT_PATH=infra/API.md
```

다음 API와 오류·idempotency·upload 계약은 `0d31576`의 정적·core 승인 범위다. 실제 endpoint 연결과 런타임 성공 증거는 아니며, backend 계약이 바뀌면 Android DTO를 다시 대조한다.

- `GET /users/me`
- `GET /feed`
- `GET /drives/{id}`
- `POST /drives`
- `PATCH /drives/{id}`
- `DELETE /drives/{id}`
- `POST /drives/{id}/uploads`
- `POST /drives/{id}/publish`
- create manifest의 `COVER`·`ROUTE`, upload refresh, private S3 presigned PUT contract
- local `clientRequestId`와 create body `clientDraftId`·`Idempotency-Key`의 동일 값 매핑
- PATCH/publish의 `expectedVersion`과 409 fail-closed 계약. 후속 구현의 durable conflict, explicit GET/UI review/adopt, adopt 뒤 publish-only replay와 Room 계측 증거는 PR #2로 통합됨
- manifest 입력의 lowercase hex SHA-256과 server 응답의 RFC 4648 base64 `x-amz-checksum-sha256` 구분

## 3. GitHub Issue 1 — 완료(정적·core 승인)

### 제목

`[P0] Android Cognito/API/S3 production adapter와 로컬 contract test`

### 완료 증거와 한계

- latest: `0d315763353a54c65352fffab2d6cb7222ec4a83`
- includes: PLEOS `89078a0` + backend `ddbd61f` cloud contract
- evidence: independent `APPROVE`, core Kotlin 41/41, npm 23/23, `수동 SQLite predicate/migration PASS`
- status: `앱 구현`; P0-1 정적·core 범위와 PR #2 통합 완료
- unavailable: concrete Cognito/API host와 no-redirect HTTP adapter 설정이 없어 actual cloud 호출 불가
- unverified: Room instrumentation, `main` 병합, Gradle, Android instrumentation, vendor adapter, APK, Pleos AVD, AWS E2E

### 권장 label

`p0`, `android`, `backend-integration`, `security`, `agent:ready`

### 목적

현재 fake `RemoteDriveApi`와 Room/WorkManager queue를 실제 backend 계약에 연결할 production adapter로 교체한다. AWS에 배포하거나 실제 credential을 사용하지 않고, MockWebServer/fixture로 인증·API·presigned upload 흐름을 검증한다.

### 범위

- `ApiConfig`를 BuildConfig 또는 non-secret local configuration에서 주입
- `AuthSessionProvider` 경계 추가
  - Cognito ID/JWT를 원격 호출 시점에 메모리로 제공
  - 토큰을 Room, WorkManager input/output, 로그에 저장하지 않음
  - Cognito 구체 구현은 최신 backend mobile client 설정과 맞는 공식 방식으로 선택
- production `RemoteDriveApi` 구현
  - `/users/me`, `/feed`, drive create/get/update/delete/upload-refresh/publish
  - cursor pagination
  - local `clientRequestId`를 create body `clientDraftId`와 `Idempotency-Key` header에 정확히 같은 값으로 전송하고 재시도 동안 불변으로 유지
  - create manifest에는 `COVER`와 `ROUTE` asset을 포함하고 각 content metadata를 backend 계약과 대조
  - PATCH와 publish에 현재 local/server state의 `expectedVersion`을 명시적으로 전송
  - 401/403/404/409/422/429/5xx를 typed error로 매핑
  - 존재하지 않거나 다른 owner의 drive에 대한 PATCH/DELETE는 구분하지 않고 동일한 UI-safe `404 NOT_FOUND`로 매핑
  - 409 version conflict에서는 automatic overwrite와 latest-version adoption을 금지하고 queue를 `FAILED/version conflict`로 유지하며 원본 draft를 보존
  - 후속 구현에는 latest GET→UI conflict 표시→사용자 명시적 review/adopt→새 `expectedVersion`의 publish-only replay 코드와 Room instrumentation 증거가 존재하며 PR #2로 통합됐다.
- S3 presigned PUT 구현
  - URL과 임시 header는 메모리에서만 사용
  - API bearer token을 S3 요청에 전달하지 않음
  - create manifest의 checksum 입력은 lowercase hex SHA-256이며, 이를 S3 header 값으로 재사용하지 않음
  - server가 presign 응답으로 돌려준 RFC 4648 base64 checksum만 `x-amz-checksum-sha256` header에 사용
  - content type, byte size, checksum과 `COVER`·`ROUTE` manifest를 server contract와 대조
  - URL 만료 시 `POST /drives/{id}/uploads`로 같은 manifest에 대해서만 재발급 요청
  - refresh 응답의 manifest가 원본과 다르면 fail closed하고 upload/publish를 중단
  - redirect host 변경, 만료 URL 재사용, checksum 불일치 fail closed
- Worker integration
  - 실행 시 fresh auth session 획득
  - Driving/Unknown motion gate를 통과한 뒤에만 network stage 시작
  - cancellation/retry에도 동일 `clientRequestId`/`clientDraftId`/`Idempotency-Key`, `expectedVersion`, manifest와 원본 draft 유지
- demo/fake와 production 구현을 flavor/DI에서 명확히 분리

### 승인된 acceptance criteria

- [x] 최신 backend `ddbd61f` 계약을 pinned contract로 기록한다.
- [x] 모든 보호 API는 `Authorization: Bearer`를 사용하고 token 부재/만료 시 remote call 없이 typed auth error를 반환한다.
- [x] token, presigned URL, raw response body, 정확한 좌표가 Room/WorkManager/log에 남지 않는다.
- [x] create 재시도는 local `clientRequestId`와 동일한 값을 body `clientDraftId` 및 `Idempotency-Key` header 양쪽에 사용하며 세 값은 재시도 동안 불변이다.
- [x] create manifest는 `COVER`와 `ROUTE`를 모두 포함하고 asset type/content type/byte size/checksum이 local upload 원본과 일치한다.
- [x] manifest checksum 입력은 lowercase hex SHA-256이고, S3 PUT의 `x-amz-checksum-sha256`은 server 응답의 RFC 4648 base64 값을 그대로 사용한다. 두 표현을 서로 대입하지 않는다.
- [x] presigned PUT에는 API authorization header가 없고 허용된 host·method·headers만 사용한다.
- [x] presigned URL 만료 시 `POST /drives/{id}/uploads`를 refresh stage로 사용하며 최초 create와 동일한 canonical manifest만 허용한다. asset 추가·삭제·교체 또는 metadata 변경은 fail closed한다.
- [x] feed cursor가 opaque string으로 왕복하며 client가 decode/조작하지 않는다.
- [x] PATCH와 publish는 `expectedVersion`을 보내며 409 version conflict에서 automatic overwrite 또는 latest-version adoption을 하지 않는다. queue는 `FAILED/version conflict`를 유지하고 original draft는 향후 명시적 review를 위해 보존한다.
- [x] PR #2 통합 소스에 409 뒤 explicit GET, UI conflict 표시, 사용자 명시적 adopt, 갱신된 `expectedVersion`의 publish-only replay를 포함한다. 일반 worker는 `FAILED/VERSION_CONFLICT`를 claim하지 않으며 Room/Compose 계측도 통과했다. actual cloud 검증은 미완료다.
- [x] 존재하지 않는 drive와 non-owner drive의 PATCH/DELETE는 모두 동일한 UI-safe `404 NOT_FOUND`로 처리되어 존재 여부를 노출하지 않는다.
- [x] validation 422와 rate-limit 429가 각각 UI-safe validation/retry 상태로 변환된다.
- [x] motion이 Parked에서 Driving/Unknown으로 바뀌면 다음 remote stage를 실행하지 않고 draft를 보존한다.
- [x] demo variant는 fake adapter와 `데모 데이터` 표시를 유지하고 production adapter로 조용히 fallback하지 않는다.
- [x] 실제 AWS 호출 없이 core test 범위가 통과한다.

### Tests

- MockWebServer contract tests
  - JWT header 주입/누락/만료
  - `clientRequestId` → body `clientDraftId` + `Idempotency-Key` exact-value mapping
  - `COVER`+`ROUTE` create manifest→presign→PUT→publish happy path
  - lowercase hex manifest checksum과 server-returned RFC 4648 base64 S3 header의 구분
  - 만료 URL→`POST /drives/{id}/uploads` refresh→같은 manifest PUT 성공
  - refresh manifest의 asset/type/size/checksum 불일치 시 PUT·publish 미호출
  - 같은 key+payload의 idempotent create replay와 같은 key+다른 payload의 409 conflict 구분
  - PATCH/publish `expectedVersion`, stale version 409→`FAILED/version conflict`, original intact, automatic GET/version adoption/replay 없음
  - cursor pagination
  - missing/non-owner PATCH·DELETE가 구분 불가능한 `404 NOT_FOUND`로 매핑됨
  - 422 validation, 429/5xx retry policy
  - presigned URL 만료 재사용, host mismatch, checksum mismatch
  - API token이 S3 request에 포함되지 않음
- WorkManager tests
  - process restart recovery
  - Parked admission 후 execution 직전 Driving/Unknown 전환
  - cancel/retry 시 original draft, `clientRequestId`/`clientDraftId`/`Idempotency-Key`, `expectedVersion`, manifest 유지
- secret/log scan
  - JWT, presigned query string, email, exact route가 log snapshot에 없음
- 가능한 범위에서 unit test, lint, demo/pleos compile을 실행하되 Gradle DNS가 막히면 실행 실패를 성공으로 기록하지 않는다.

### Dependencies

- approved integrated head: `0d31576` (includes `issue-pleos-integration@89078a0`)
- contract: `agent/issue-backend-mvp@ddbd61f`
- backend의 mobile Cognito client가 client secret 없는 public client인지 확인
- P0-2와 공유하는 route DTO는 실제 좌표를 전제로 확장하지 않고 preset reference를 기본으로 유지

### Approval boundary

- 허용: issue workspace 내부 코드 변경, local dependency 설치, MockWebServer test, local build
- 금지: AWS login/credential 읽기, real Cognito user 생성, 실제 API/S3 호출, CloudFormation change set 생성·실행, deploy
- PR 작성 전 secret scan과 test evidence 필요
- push/PR 생성은 host orchestrator, merge는 Discord 승인 이후에만 수행

## 4. GitHub Issue 2 — 완료(정적·core 승인)

### 제목

`[P0] Coming Soon-safe route source와 위치 동의·철회 경계`

### 완료 증거와 한계

- current descendant: `4f2b559116fd98ee3fe05e05fc6431b59888d0d6`
- P0-2 approval commit: `b1c33a22b18c62806ee89337ef04635c53dd302a`
- review: Sentinel `APPROVE`
- current evidence: Kotlin 74/74, static 29/29, PLEOS instrumentation 51/51; Room schema v6/conflict/delete lifecycle 포함
- status: `앱 구현`; PR #2 통합 완료, actual cloud·signed release 미완료
- actual location: unavailable이며 route point 수집 없음
- published delete: Room v6 tombstone, network `DELETE`, bounded retry 계약과 계측 존재; actual cloud는 unavailable

### 권장 label

`p0`, `android`, `privacy`, `automotive-safety`

### 목적

실제 위치·route point 수집이 없는 현재 상태를 코드와 UI에서 정직하게 표현한다. FusedLocation이 Coming Soon인 동안 live collection을 구현했다고 주장하지 않고, 향후 허용된 source가 생겨도 명시적 동의 전에는 수집할 수 없는 구조를 만든다.

### 범위

- `RouteSource`를 명시적으로 모델링
  - `DemoPreset`
  - `LiveUnavailable(SdkComingSoon | PermissionDenied | CapabilityMissing)`
  - `LiveAvailable`은 검증된 공식 source가 생기기 전 production에서 생성 불가
- demo route point에는 source metadata를 붙이고 모든 화면에 `데모 데이터` 표시
- production/release manifest에는 검증되지 않은 location permission을 추가하지 않음
- 위치 동의 state machine 설계·구현
  - NotAsked → Granted → Withdrawn
  - 동의 전 collector 시작 불가
  - 철회 즉시 collector stop, unpublished route point purge, 관련 pending work cancel
- 개인정보 화면에 수집 목적·항목·보존·공개 범위·철회 결과 표시
- 실제 위치 source가 없으면 CTA는 unavailable 사유를 보여 주고 수집하지 않음
- preset ref는 실제 route 또는 Vehicle callback으로 직렬화하지 않음

### 승인된 acceptance criteria와 후속 범위

> 아래 체크리스트는 당시 SDK 미통합 상태의 역사 기록이다. 현재 위치·TTS·STT 상태는 [2026-11-07 PLEOS 실행 증거](../pleos-runtime-evidence-2026-11-07.md)와 `official-requirements.md`의 최신 truth table을 우선한다.

- [x] source/manifest 정적 검사와 Gradle-resolved build에서 FusedLocation/TTS/STT/Vehicle Control dependency와 permission이 없음.
- [x] 현재 production에서 live route collection을 시작할 수 있는 code path가 없음.
- [x] demo preset은 DB/API/UI에서 `DEMO` source로 구분되고 실제 위치로 표시되지 않음.
- [x] source unavailable 상태에서 실제 위치 consent를 받아 둔 것처럼 표시하지 않음.
- [x] explicit consent와 available source 없이는 route point 0개를 유지함.
- [x] 철회 시 unpublished local data purge와 관련 pending work cancel을 fail-closed 처리함.
- [x] published delete 요청은 Parked에서만 Room v6 tombstone으로 보존하며 Driving/Unknown에서는 차단함.
- [x] tombstone을 backend `DELETE /drives/{id}` 계약과 bounded retry lifecycle에 연결하고 demo transport에서 계측함. actual cloud는 미검증.
- [x] 위치·좌표·동의 상태 원문이 로그에 노출되지 않음.
- [x] 문서에서 실제 위치 수집은 `미구현`, preset은 `데모 데이터`로 유지함.

### Tests와 남은 경계

- current descendant 실행 증거: Kotlin 74/74, static 29/29, PLEOS instrumentation 51/51, Gradle 179 tasks
- 정적·core 범위: consent reducer, no-consent/no-source zero route points, demo metadata, withdrawal/purge/cancel, forbidden dependency/permission source check
- DB 실행 증거: Room schema v6 migration/persistence와 conflict/delete DAO instrumentation
- 남은 검증: actual cloud, 실제 위치 source, signed release, 실제 Vehicle callback, NaviHelper UI 전체 여정·경유지 실행·물리 차량과 physical Back

### Dependencies

- base `0d31576`, P0-2 approved result `b1c33a2`, current descendant `4f2b559`
- P0-1에서 승인된 API DTO와 deletion error model
- 실제 live collector는 공식 SDK 상태 변경 또는 공식 AAOS source/permission 확인을 다루는 별도 Issue가 승인되기 전까지 scope 밖

### Approval boundary

- 위치 permission 추가, 실제 위치 collection 활성화, 개인정보 정책 확정은 별도 human approval 필요
- 실제 사용자·차량 데이터 수집 금지
- AWS deploy/credential 접근 금지
- merge는 Discord 승인 이후에만 수행

## 5. GitHub Issue 3 — 진행 중, 제출 release·실기 증거 보완

### 제목

`[P0] demo/release APK, Pleos AVD smoke matrix와 제출 증거 패키지`

### 권장 label

`p0`, `android`, `release`, `contest-submission`

### 목적

정적 검토 코드를 실행 가능한 APK와 재현 가능한 증거로 전환한다. signing secret을 저장소에 넣지 않고 demo와 Pleos runtime을 분리하며, 확인된 사실만 README·영상·PT에 반영한다.

역사적 HEAD `88b4938`의 wrapper/platform/DNS 차단은 해소됐다. HEAD `4f2b559`에서 tasks·assemble·unit/lint·Android instrumentation·Pleos AVD 실행과 build APK 생성을 확인했다. 이후 Issue #28 exact smoke는 NaviHelper 공개 목적지 1건의 application-boundary callback·cleanup·release를 확인했다. 제출용 signed release·hash·clean install/relaunch·영상, 실제 Vehicle callback과 NaviHelper UI 전체 여정은 계속 미완료다.

### P0-3A 부분 완료 증거

권장 선행 Issue: `[P0-3A] Local Android/Pleos toolchain unblock — no signing/deploy`

확인됨:

- macOS 14.5 arm64, Android Studio 2026.2
- JDK 17.0.13 설치
- Android SDK platform 34, build-tools 36.0.0, adb 37.0.1과 Pleos API34 AVD 정의
- `aapt2`, `d8`, `zipalign`, `apksigner` 존재
- `88b4938`에 reproducible local bootstrap/doctor 추가
- core Kotlin 51/51, npm 26/26, Sentinel `APPROVE`
- offline 실행은 네트워크 요청 0건으로 missing prerequisite를 즉시 보고
- 호스트에 별도 Gradle 8.14.3 cache가 있지만 project-required wrapper 8.9 cache와는 무관함

역사적 차단(현재 해소):

- project-required Gradle wrapper 8.9 distribution cache 없음
- exact Android platform 35 없음
- `services.gradle.org` DNS 차단으로 wrapper 8.9 다운로드 불가
- AGP, Kotlin Gradle plugin, Pleos/Vehicle/NaviHelper compile artifact 준비 확인 전
- `sdkmanager`·`avdmanager` 없음
- Gradle tasks 목록과 variant 발견 단계에 도달하지 못함
- assemble, vendor compile, adb device, AVD boot, install, instrumentation, callback은 실행하지 않음
- signing secret·keystore는 조회하지 않음

`88b4938`의 bootstrap/doctor 실패 기록은 당시 상태일 뿐이다. 현재 성공 증거는 `4f2b559`의 별도 실행 결과를 기준으로 한다.

### 범위

- Gradle wrapper/DNS/Maven dependency 문제 해결 및 로그 보존
- `demoRelease`와 `pleosRelease` 의존성 분리
  - demo는 Pleos SDK 없이도 빌드 가능
  - pleos는 Vehicle 2.3.2/NaviHelper 3.0.5를 compile
- release signing configuration
  - keystore path/password는 local/CI secret으로만 주입
  - debug key로 release artifact를 가장하지 않음
- test matrix
  - JVM/unit/instrumented tests
  - standard API34 demo smoke
  - Pleos Emulator 2.1.0/API34 cold boot
  - P/D/R/N, Unknown, permission denied, service unavailable
  - Navi destination/waypoint callback
  - offline/restart/publish queue
- artifact
  - `apk/팀명_SocialDrive_release.apk`
  - SHA-256, commit SHA, build command, AVD version 기록
- README·truth table·90~150초 내부 권장 영상·7장 내부 권장 PT를 실제 결과로 갱신

### Acceptance criteria

- [x] reproducible local bootstrap/doctor를 제공함.
- [x] offline mode에서 네트워크 요청 없이 missing wrapper/platform을 fail-fast 보고함.
- [x] project-required Gradle 8.9와 exact Android platform 35를 다른 설치/cache와 구분함.
- [x] wrapper 8.9 cache와 Android platform 35를 준비함.
- [x] Gradle tasks와 실제 demo/pleos variant를 확보함.
- [x] 검토 workspace에서 clean command로 demo release build를 재현함(unsigned).
- [ ] pleos release가 compile/assemble되고 Pleos AVD에 설치·재실행됨.
- [ ] Vehicle/NaviHelper callback을 화면 녹화와 최소 비민감 로그로 증명함.
- [ ] FusedLocation/STT/TTS/Vehicle Control을 실제 기능으로 표시하지 않음.
- [ ] APK가 목표 경로에 있고 SHA-256과 source commit이 README에 기록됨.
- [ ] signing secret, keystore, token, webhook, AWS account 정보가 Git/로그/APK 문서에 없음.
- [ ] demo 화면은 지속적인 `데모 데이터` 배지를 표시함.
- [ ] 실패한 AVD/API 시나리오도 결과표에 남기고 성공으로 표현하지 않음.
- [ ] 기획서·README·영상·PT의 기능 등급이 동일함.

### Tests

- `git diff --check`, secret scan, dependency/manifest scan
- Gradle unit/lint/assemble 명령과 exit code 기록
- release APK 설치·launch·process restart smoke
- Pleos Virtual Console matrix
- no-network queue recovery
- APK unpack scan for `.env`, key, token, test credential, debug endpoint

### Dependencies

- P0-3A doctor 승인 HEAD `88b4938` (`b1c33a2` 포함)
- project-required Gradle 8.9와 Android platform 35 확보 필요
- signing material은 human/CI가 제공하며 worker는 값을 읽거나 출력하지 않음
- Pleos SDK Maven/AVD 접근 가능 환경

### Approval boundary

- worker는 local build/test만 수행
- signing secret 생성·열람·출력 금지; human/CI 주입만 허용
- GitHub Release publish, repository APK push, merge는 별도 승인
- AWS deploy, IAM 변경, real API smoke는 이 Issue 범위 밖

## 6. P0-3A 부분 완료 기록과 runtime handoff

```text
Historical P0-3A doctor head: 88b493860ddc18e2131af723463fe68e43cf515f
Base: b1c33a22b18c62806ee89337ef04635c53dd302a
Backend contract: ddbd61fde381d09bc4605c758126ebf6ba0a5ea5
Review: Sentinel APPROVE
Historical core Kotlin at doctor head: 51/51
Historical npm at doctor head: 26/26
P0-3A: bootstrap/doctor + offline zero-network fail-fast
Missing: wrapper 8.9 cache + exact Android platform 35
Blocked: services.gradle.org DNS
Not reached: tasks/variants/assemble
Unrun: vendor compile/instrumentation/APK/AVD/AWS E2E
```

P0-3A 역사 기록의 완료 판정은 bootstrap/doctor와 fail-fast 범위다. 후속 `4f2b559`가 build APK와 PLEOS instrumentation 증거를 추가했다.

다음 실행은 PR #2가 통합된 현재 소스에서 수행한다. dependency/vendor Maven 접근과 signing은 별도 승인 경계를 지키고, 제출용 release는 source SHA·서명·hash·clean install/relaunch를 함께 기록한다.

### P0-3 남은 완료 조건

- [x] PR #2 병합 커밋 `dcffaad`를 제출 소스에 통합
- signed submission release 생성과 SHA-256 기록
- clean Pleos AVD install/relaunch 및 영상
- 실제 Vehicle callback, NaviHelper UI 전체 여정·경유지 실행·물리 차량과 physical system Back 수용 시험
- actual cloud 사용 여부 결정 및 Android-AWS E2E

위 조건 전에는 제출용 signed APK, 실제 SDK, AWS E2E 또는 release 준비 완료로 표시하지 않는다.

## 7. 전체 완료 정의

세 Issue가 끝나도 다음 조건 전에는 `실제 SDK`, `배포 완료`, `release 준비 완료`로 표현하지 않는다.

- Android changes가 main에 병합됨
- vendor adapter compile과 Gradle assemble 성공
- Pleos AVD runtime과 Vehicle/Navi callback 증거 확보
- Android↔backend local contract test 통과
- 실제 위치 미구현 또는 demo 상태가 모든 산출물에서 일치
- APK 설치·재시작과 checksum 검증
- README·기획서·영상·PT truth table 일치
