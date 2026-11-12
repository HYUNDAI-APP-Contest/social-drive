# AWS 앱 연결 인계

이 문서는 승인된 CloudFormation 배포 후 PLEOS 앱에 비밀값 없이 AWS 출력값을 연결하는 절차다. 작업자는 AWS 배포, IAM 변경, 운영 데이터 변경을 수행하지 않는다.

## CloudFormation 출력 매핑

| CloudFormation 출력 | Android 빌드 속성 | 용도 |
|---|---|---|
| `ApiUrl` | `socialDrive.cloud.apiBaseUrl` | HTTPS API Gateway 기본 URL |
| `UploadHost` | `socialDrive.cloud.uploadHosts` | presigned S3 업로드 호스트 허용 목록 |
| 정책 승인 버전 | `socialDrive.cloud.consentPolicyVersion` | 게시 요청에 포함할 개인정보 동의 버전 |
| 배포 리전 | `socialDrive.cloud.cognitoRegion` | User Pool 리전 검증 |
| `UserPoolId` | `socialDrive.cloud.cognitoUserPoolId` | 로그인 어댑터 구성 |
| `UserPoolClientId` | `socialDrive.cloud.cognitoClientId` | secret 없는 모바일 클라이언트 구성 |

비밀키, Client Secret, 사용자 토큰, presigned URL은 Gradle 속성이나 APK에 넣지 않는다. 여섯 Gradle 속성은 명령행 `-P` 또는 저장소 밖의 사용자 Gradle 설정으로 전달한다.

```text
-PsocialDrive.cloud.apiBaseUrl=https://.../prod
-PsocialDrive.cloud.uploadHosts=bucket.s3.ap-northeast-2.amazonaws.com
-PsocialDrive.cloud.consentPolicyVersion=2026-11-07.v1
-PsocialDrive.cloud.cognitoRegion=ap-northeast-2
-PsocialDrive.cloud.cognitoUserPoolId=ap-northeast-2_XXXXXXXXX
-PsocialDrive.cloud.cognitoClientId=xxxxxxxxxxxxxxxxxxxxxxxxxx
```

출력값을 받은 뒤 APK를 만들기 전에 같은 `-P` 인자와 함께 다음 게이트를 실행한다. 이 작업은 값을 로그에 출력하지 않고 누락, HTTP URL, 스킴이 포함된 업로드 호스트, 앱 동의 정책과 다른 정책 버전, 리전이 다른 User Pool, 잘못된 모바일 Client ID를 거부한다. API도 `2026-11-07.v1`과 다른 동의 버전의 초안 생성·업로드 갱신·최종 게시를 거부한다.

```text
./gradlew verifyPleosCloudConfiguration
```

`prePleosReleaseBuild`가 이 검증 작업에 의존하므로 PLEOS release APK·Bundle은 여섯 값이 누락되거나 형식 검증에 실패하면 자동으로 중단된다. Demo release와 debug 개발 빌드는 실제 AWS 출력값을 요구하지 않는다. 공개 출력값이 준비되지 않은 상태에서 이 게이트를 우회해 PLEOS 제출 APK를 만들지 않는다.

PLEOS 빌드는 공식 Amplify Android Cognito 플러그인의 SRP 로그인을 사용한다. 앱 시작 시 SDK가 안전하게 관리하는 기존 세션을 복원하고, 설정 화면에서 아이디·비밀번호 로그인과 문자/TOTP 추가 인증 및 로그아웃을 제공한다. 비밀번호와 인증 응답은 Compose 저장 상태, Room, WorkManager 입력, 로그에 넣지 않으며 제출 직후 화면 상태와 전달 배열에서 지운다. SDK 세션에서 얻은 현재 액세스 토큰은 매 API 작업 시 `CognitoSessionSource`를 통해 요청하고 앱 런타임에는 별도로 영속화하지 않는다.

클라이언트는 API 요청 직전에 JWT의 `iss`, `client_id`, `token_use=access`, `iat`, `exp`를 검증한다. 승인된 User Pool·모바일 Client ID와 다르거나 만료가 30초 이내인 토큰은 네트워크로 보내지 않고 `AUTH_SESSION_UNAVAILABLE`로 중단한다. JWT 서명 검증은 Cognito JWT authorizer가 담당하며 앱은 서명 검증 성공을 주장하지 않는다.

## 승인 후 검증

1. 설정 화면에 `AWS · 로그인 필요`가 표시되는지 확인한다.
2. 설정의 `AWS 계정`에서 Cognito 로그인 및 구성된 MFA를 완료한 뒤 `AWS · 로그인 검증됨`과 `AWS 계정 연결됨`으로 바뀌는지 확인한다.
3. 게시 생성, COVER/ROUTE 업로드, publish ACK 순서가 성공하는지 확인한다.
4. 업로드된 ROUTE JSON에 `horizontal`, `vertical`, `progress` 외 원본 위치 키가 없는지 확인한다.
5. 로그아웃 뒤 게시 요청이 `CONFIGURATION` 또는 인증 실패로 안전하게 중단되는지 확인한다.
6. 다른 User Pool/Client ID, ID token, 만료 임박 access token이 네트워크 요청 전에 거부되는지 확인한다.

실제 E2E에는 승인된 스택 출력값과 테스트 사용자만 필요하다. 앱 빌드나 로그인 검증 과정에서 AWS 자격 증명, Client Secret, IAM 권한은 필요하지 않으며 저장소 작업자가 사용하거나 변경하지 않는다.
