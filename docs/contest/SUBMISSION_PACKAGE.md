# RoadStory 공모전 제출 패키지

기준일: 2026-11-12 KST
앱 이름: **RoadStory | 로드스토리**

## 제출 전략

필수인 접수양식(기획서)에 더해 시연 영상, GitHub 저장소, 소스 코드와 실행 가능한 release APK를 1차 접수에 함께 제출해 개발 산출물 가산점을 목표로 한다. 2차 PT 대상 선정 전에도 같은 저장소에서 앱을 계속 고도화할 수 있으나, 접수 메일에 적는 최종 커밋은 당시 제출 산출물의 정확한 기준점이어야 한다.

## 산출물 상태

| 번호 | 산출물 | 제출 여부 | 저장소 위치 | 현재 상태 |
|---:|---|---|---|---|
| 01 | 접수양식(기획서) | 필수 | `docs/contest/2026-application-draft.md` | 초안 존재, 실제 앱 화면·서명·최종 URL 반영 필요 |
| 02 | 시연 영상 | 선택·제출 권장 | `video/RoadStory_PLEOS_promo_2026.mp4` | 최신 PLEOS 촬영·편집본 완료(61,217,807 bytes); URL 등록 필요 |
| 03 | GitHub 저장소 | 선택·제출 권장 | `[최종 GitHub URL]` | 로컬 소스 존재, 공개 전 비밀·대용량·라이선스 검사 필요 |
| 03-A | release APK | GitHub와 함께 제출 | `apk/용용_RoadStory_release.apk` | signed Pleos release 생성·정적 검증 완료 |
| 04 | 숏폼 링크 | 선택 | 기획서에 `[숏폼 URL]` | 미정; 가산점 없음 |

## README 공식 요구사항

- [x] 앱 이름 및 한 줄 소개
- [x] 실행 방법
- [x] 테스트 환경 및 필요한 설정
- [x] Pleos Connect Emulator 버전과 화면 규격
- [x] APK 목표 위치
- [ ] 실제 APK 또는 GitHub Release 링크
- [ ] 최종 APK SHA-256과 source commit

## 제출 디렉터리

```text
readme.md
apk/
  용용_RoadStory_release.apk
docs/contest/
  2026-application-draft.md
  SUBMISSION_PACKAGE.md
video/
  RoadStory_PLEOS_promo_2026.mp4
android/
  app/...
  scripts/build-submission-release.sh
  scripts/verify-pleos-clean-install.sh
```

## 시연 영상 권장 구성

1. 0–10초: RoadStory 문제와 한 줄 가치 제안
2. 10–25초: 3열 소비자 피드와 실제 도로형 경로, 카메라 장면
3. 25–40초: 게시물 선택과 Gleo의 “이 순간을 경험할까요?” 질문
4. 40–75초: 동의 직후 영상·음악 정보·조수석 창문·앰비언트가 한 번에 시작되는 30초 경험
5. 75–90초: 종료 시 차량 원상복구와 PLEOS SDK 연결 상태
6. 90–110초: 기록 생산자 흐름과 “방금 좋았어” 음성 하이라이트
7. 110–120초: RoadStory 로고, GitHub와 APK 제출 표시

영상에는 실제 SDK 동작, 화면 전용 데모, 향후 제휴 기능을 구분해서 표시한다. 공개 PLEOS SDK에는 제3자 앱용 전방 카메라 원본 스트림 API가 확인되지 않았으므로, 승인 전에는 데모 주행 영상이나 사용자가 명시적으로 제공한 영상만 사용하고 “현대차 전방 카메라 촬영”이라고 주장하지 않는다.

영상 소스와 실차 연동 경계는 `docs/contest/vehicle-camera-media-strategy.md`를 기준으로 한다.

## release APK 완료 조건

- [x] 최종 팀명 `용용` 확정 및 제출 파일명 반영
- [ ] 검토된 clean commit에서 release 빌드
- [ ] 승인된 인증서로 서명
- [x] 파일명 `용용_RoadStory_release.apk`
- [x] `apk/`에 배치
- [ ] 깨끗한 PLEOS AVD 신규 설치·최초 실행·재실행
- [ ] 핵심 사용자 시나리오 무중단 재생
- [ ] APK 내부 credential·private key·webhook·debug endpoint 없음
- [x] APK SHA-256 기록 (`58c659ef7b3d097dc052950c332cfd069e99941973022d96deb8af2505e1ea99`)
- [ ] 접수 메일에 저장소 URL, APK 경로, 최종 40자리 commit hash 기록

## 2026-11-12 로컬 제출 검증 결과

- Demo/Pleos debug APK 조립과 양 flavor 단위 테스트: **PASS** (`BUILD SUCCESSFUL`, 96 tasks)
- Android 아키텍처·제품 경계 계약: **41/41 PASS**
- AWS 인프라 계약·보안 테스트: **50/50 PASS**
- 호스트 오케스트레이터 검사: **112/112 PASS**
- release/clean-install 스크립트 셸 문법 검사: **PASS**
- Git diff whitespace 검사: **PASS**
- 제출 영상: `61,217,807 bytes`, SHA-256 `0ed23095c9608f40144286b85f744e8291c01de2c5298f056118c09d9c72d69f`
- 저장소 credential 패턴 검사는 테스트 fixture와 설정 스크립트의 패턴 문자열만 탐지했다. 추적 파일은 `.env.example`뿐이며 release keystore는 추적되지 않는다.
- Android 전체 Node 계약 테스트는 **60/61 PASS**다. 남은 1개는 테스트가 자식 Gradle 프로세스를 띄우는 단계에서 worker sandbox의 로컬 소켓 제한으로 실행되지 않았다. 같은 검사를 허용된 직접 Gradle 경로로 재실행해 축약 release artifact task가 서명 환경 누락 시 의도대로 실패하고, 비산출물 `lintDemoRelease` task는 성공하는 것을 확인했다.

## 호스트 즉시 인계

`APPROVAL_NEEDED / SUBMISSION RELEASE`: worker 정책상 서명 키, GitHub 자격 증명, Discord 승인, AWS 계정에 접근하지 않는다. 호스트 오케스트레이터는 다음 순서로 중단 없이 처리한다.

1. 현재 변경을 검토해 정확한 파일 allowlist로 제출 후보 커밋을 만든다.
2. worker 브랜치를 push하고 `main` 대상 PR을 열어 Discord 병합 승인을 받는다.
3. 병합된 clean commit에서 저장소 밖 release signing material을 주입하고 `android/scripts/build-submission-release.sh`를 실행한다.
4. `android/scripts/verify-pleos-clean-install.sh`로 깨끗한 PLEOS AVD 설치·최초 실행·재실행을 검증한다.
5. 최종 Pleos APK 크기가 GitHub 단일 파일 제한 이내면 `apk/용용_RoadStory_release.apk`로 배치하고, 초과하면 GitHub Release에 첨부한다.
6. APK SHA-256, 40자리 source commit, 저장소 URL, APK 경로 또는 Release URL을 이 문서와 접수 메일에 기록한다.
7. 병합된 새 source SHA로 AWS change set을 만들고 별도 Discord 배포 승인 후 실행한다. 실패한 source SHA `972f16c044a7`과 기존 change set은 재사용하지 않는다.

## GitHub 공개 전 점검

- [ ] `.env`, keystore, AWS credential, Discord token, 테스트 비밀번호가 추적되지 않음
- [ ] 스톡 영상·이미지의 사용권과 출처 기록
- [ ] APK가 GitHub 단일 파일 제한을 넘으면 GitHub Releases 사용
- [ ] 비공개 제출 시 안내된 날짜까지 Public 전환
- [ ] 1차 심사 결과 발표일까지 Public 유지
- [ ] README 링크와 실제 파일 경로 일치

## 제출 직전 교체할 값

```text
팀명: 용용
GitHub 저장소: [최종 GitHub URL]
최종 commit: [40자리 commit hash]
APK: apk/용용_RoadStory_release.apk 또는 [GitHub Release URL]
APK SHA-256: [sha256]
시연 영상: video/RoadStory_PLEOS_promo_2026.mp4 또는 [최종 영상 URL]
숏폼: [숏폼 URL 또는 제출 안 함]
```

## 현재 차단 사항

- 현재 작업 트리는 검토 전 변경을 포함하므로 제출 release의 source commit으로 사용할 수 없다.
- release signing material은 저장소 밖에서 주입해야 하며 worker가 읽거나 저장하지 않는다.
- 실제 전방 카메라 영상 접근은 공개 PLEOS SDK 범위가 아니므로 별도 플랫폼 승인이 필요하다.
