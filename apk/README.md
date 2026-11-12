# RoadStory 제출 APK

공모전 제출용으로 검증된 signed release APK만 이 폴더에 둡니다.

최종 파일명:

```text
용용_RoadStory_release.apk
```

현재 이 폴더에는 signed Pleos release APK가 있습니다. 디버그 APK나 unsigned release를 제출 파일로 복사하지 않습니다.

```text
파일: apk/용용_RoadStory_release.apk
크기: 33,884,939 bytes
SHA-256: 58c659ef7b3d097dc052950c332cfd069e99941973022d96deb8af2505e1ea99
패키지: com.socialdrive.app
서명 인증서: CN=RoadStory, OU=YongYong, O=YongYong, L=Seoul, ST=Seoul, C=KR
```

2026-11-13에 APK Signature Scheme v3 서명, `debuggable=false`,
`allowBackup=false`, `usesCleartextTraffic=false`, 민감 파일·private key·webhook·로컬
debug endpoint 미포함 및 체크섬 일치를 확인했습니다. 별도의 깨끗한 PLEOS AVD
신규 설치·재실행 증거는 아직 추가 검증 대상으로 남아 있습니다.

최종 APK는 다음 조건을 충족하도록 관리합니다.

- 검토된 최종 커밋에서 생성
- 승인된 release 인증서로 서명
- Pleos Connect Emulator 2.1.0의 깨끗한 AVD에 신규 설치
- 최초 실행, 강제 종료, 재실행 성공
- 비밀키·토큰·웹훅·디버그 엔드포인트 검사 통과
- SHA-256과 source commit 기록

생성 절차는 `android/scripts/build-submission-release.sh`와
`android/scripts/verify-pleos-clean-install.sh`를 사용합니다.

## GitHub 제출 위치

서명·clean-install 검증이 끝난 `RoadStory_pleos_release.apk`만 다음 이름으로 승격합니다.

```text
apk/용용_RoadStory_release.apk
```

- 최종 파일이 GitHub 단일 파일 제한 이내이면 위 경로로 커밋하고, 제출 페이지에 저장소 URL과 해당 경로를 적습니다.
- 제한을 넘으면 저장소에는 이 안내와 release SHA-256을 남기고, APK는 GitHub Releases에 첨부한 뒤 Release 페이지 URL을 제출합니다.
- `app-pleos-debug.apk`, `app-demo-debug.apk`, unsigned release는 크기와 관계없이 제출하지 않습니다.

2026-11-13 현재 제출용 signed release는 33,884,939 bytes로 GitHub 단일 파일 제한
이내입니다. 따라서 GitHub Release가 아니라 저장소의
`apk/용용_RoadStory_release.apk` 경로로 직접 제출할 수 있습니다.
