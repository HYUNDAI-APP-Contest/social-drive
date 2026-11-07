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

로그인 어댑터는 `PleosCloudRuntime.configuration.cognito`의 검증된 공개 식별자를 사용한다. 현재 액세스 토큰을 매 요청마다 반환하는 `CognitoSessionSource`를 구현하고 로그인 성공 뒤 `PleosCloudRuntime.installSessionSource(source)`에 등록한다. 로그아웃 때는 `PleosCloudRuntime.clearSessionSource()`를 호출한다. 토큰은 Room, WorkManager 입력, 로그에 저장하지 않는다.

## 승인 후 검증

1. 설정 화면에 `AWS · 로그인 필요`가 표시되는지 확인한다.
2. Cognito 로그인 뒤 `AWS · 로그인 연결됨`으로 바뀌는지 확인한다.
3. 게시 생성, COVER/ROUTE 업로드, publish ACK 순서가 성공하는지 확인한다.
4. 업로드된 ROUTE JSON에 `horizontal`, `vertical`, `progress` 외 원본 위치 키가 없는지 확인한다.
5. 로그아웃 뒤 게시 요청이 `CONFIGURATION` 또는 인증 실패로 안전하게 중단되는지 확인한다.
