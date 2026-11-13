# RoadStory 공모전 화면 캡처 목록

촬영 환경: Pleos Connect Emulator 2.1.0, Android API 34, 2560×1440 전체 화면

촬영일: 2026-11-12 KST

| 파일 | 장면 | 기획서/슬라이드 활용 |
|---|---|---|
| `01-feed-offline-demo.png` | 개인 위치가 제거된 드라이브 경험 피드 | 핵심 솔루션, 콘텐츠 탐색, 오프라인 |
| `02-drive-detail.png` | 경로·하이라이트·차량 내비게이션 연결 | NaviHelper, 코스 선택 |
| `03-motion-unknown-safety-gate.png` | 주차 신호 미확인 안전 잠금 | Vehicle SDK, fail-closed 안전 정책 |
| `04-recording-active.png` | 경로 기록 중 | 생산자 흐름, 원본 좌표 미저장 |
| `05-highlight-saved.png` | `방금 좋았어` 하이라이트 1개 저장 | 주행 중 큰 버튼·Gleo 제한 음성 명령 |
| `06-recording-stopped-draft.png` | 기록 종료 후 피드 복귀 | 안전한 종료, 로컬 초안 보존 |
| `07-gleo-experience-start.png` | 30초 하이라이트 경험 시작 | Gleo Interface, 영상·차량 환경 재현 |
| `08-gleo-experience-window-cue.png` | 창문 cue 구간 | Vehicle Window 연결/화면 재생 상태 |
| `09-gleo-experience-ambient-cue.png` | 앰비언트 cue 구간 | Vehicle Light 연결/화면 재생 상태 |
| `10-gleo-experience-complete.png` | 경험 종료 후 상세 복귀 | 종료·이탈 및 차량 상태 원상복구 설명 |
| `11-gleo-voice-entry.png` | Gleo `듣는 중` 진입 | Pleos STT, 손을 쓰지 않는 조작 |
| `12-settings-sdk-status.png` | 실제 SDK·위치·음성·클라우드 상태 | 기술 활용성, 개인정보, 데모/실제 경계 |

## 표기 주의

- 이미지의 `데모 경로`는 정밀 실경로가 아닌 표시용 정규화 데이터다.
- 하이라이트 경험의 창문·조명은 화면 시퀀스와 SDK 상태를 구분해 표시한다. 실차 제어는 권한·PARK·capability·사용자 동의가 모두 확인된 경우에만 수행한다.
- 주행 영상/대표 이미지는 제출용 시각 자료이며, 공개 PLEOS SDK가 제3자 앱에 전방 카메라 원본 스트림을 제공한다는 주장이 아니다.
