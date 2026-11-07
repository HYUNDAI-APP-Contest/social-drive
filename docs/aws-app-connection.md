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
-PsocialDrive.cloud.consentPolicyVersion=2026-11-07
-PsocialDrive.cloud.cognitoRegion=ap-northeast-2
-PsocialDrive.cloud.cognitoUserPoolId=ap-northeast-2_XXXXXXXXX
-PsocialDrive.cloud.cognitoClientId=xxxxxxxxxxxxxxxxxxxxxxxxxx
```

출력값을 받은 뒤 APK를 만들기 전에 같은 `-P` 인자와 함께 다음 게이트를 실행한다. 이 작업은 값을 로그에 출력하지 않고 누락, HTTP URL, 스킴이 포함된 업로드 호스트, 리전이 다른 User Pool, 잘못된 모바일 Client ID를 거부한다.

```text
./gradlew verifyPleosCloudConfiguration
```

로그인 어댑터는 `PleosCloudRuntime.configuration.cognito`의 검증된 공개 식별자를 사용한다. 현재 액세스 토큰을 매 요청마다 반환하는 `CognitoSessionSource`를 구현하고 로그인 성공 뒤 coroutine에서 `PleosCloudRuntime.installSessionSource(source)`를 호출한다. 이 함수가 `true`를 반환해야 설정 화면이 `AWS · 로그인 검증됨`으로 바뀐다. 잘못된 토큰 또는 예외를 반환한 공급자는 저장하지 않는다. 로그아웃 때도 coroutine에서 `PleosCloudRuntime.clearSessionSource()`를 호출한다. 토큰은 Room, WorkManager 입력, 로그에 저장하지 않는다.

클라이언트는 API 요청 직전에 JWT의 `iss`, `client_id`, `token_use=access`, `iat`, `exp`를 검증한다. 승인된 User Pool·모바일 Client ID와 다르거나 만료가 30초 이내인 토큰은 네트워크로 보내지 않고 `AUTH_SESSION_UNAVAILABLE`로 중단한다. JWT 서명 검증은 Cognito JWT authorizer가 담당하며 앱은 서명 검증 성공을 주장하지 않는다.

## 승인 후 검증

1. 설정 화면에 `AWS · 로그인 필요`가 표시되는지 확인한다.
2. Cognito 로그인 뒤 설치 함수가 `true`를 반환하고 `AWS · 로그인 검증됨`으로 바뀌는지 확인한다.
3. 게시 생성, COVER/ROUTE 업로드, publish ACK 순서가 성공하는지 확인한다.
4. 업로드된 ROUTE JSON에 `horizontal`, `vertical`, `progress` 외 원본 위치 키가 없는지 확인한다.
5. 로그아웃 뒤 게시 요청이 `CONFIGURATION` 또는 인증 실패로 안전하게 중단되는지 확인한다.
6. 다른 User Pool/Client ID, ID token, 만료 임박 access token이 네트워크 요청 전에 거부되는지 확인한다.
