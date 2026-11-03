# Social Drive AWS infrastructure

Low-cost serverless infrastructure for `dev` and `staging` in `ap-northeast-2`. This scaffold creates no resources by itself.

## Included

- Cognito User Pool and public mobile client without a client secret
- API Gateway HTTP API with Cognito JWT authorization
- One ARM64 Lambda router with reserved concurrency
- DynamoDB on-demand single table with feed GSI and point-in-time recovery
- Private, encrypted, versioned S3 media bucket with public access blocked
- CloudWatch logs with 14-day retention and error/throttle alarms
- Optional monthly AWS Budget email alert
- Idempotent draft/upload/publish API, owner-only mutations, and opaque TTL feed cursors

## Approval boundary

Agents may run tests, `sam validate`, `sam build`, and create a CloudFormation change set. They may not execute a change set, deploy, delete a stack, mutate IAM, or access application data. The host orchestrator must send the exact change set summary to Discord and receive an approval before execution.

## Local verification

```bash
npm --prefix infra test
node --check infra/functions/api/production-adapters.mjs
sam validate --lint --template-file infra/template.yaml
sam build --template-file infra/template.yaml
```

The Android–backend contract can also be exercised locally against the real handler and an in-memory repository, without AWS credentials or deployment:

```bash
bash android/scripts/test-contract-harness.sh
```

See [the contract harness guide](../docs/android-backend-contract-harness.md) for covered lifecycle, failure-state, and privacy assertions.

## Planned deployment sequence

1. Authenticate with the local AWS CLI profile `social-drive-deployer`; keys are never copied into project files.
2. Confirm the account and region with `aws sts get-caller-identity`.
3. Package and create a named CloudFormation change set for `social-drive-dev`.
4. Post its resource and IAM summary to Discord.
5. Execute only after explicit Discord approval.
6. Run smoke tests and report outputs without tokens or personal data.

Never place AWS access keys, account IDs, JWTs, precise routes, or raw vehicle data in the repository or logs.

The scripts under `infra/bootstrap/` create only the private SAM artifact bucket and restricted CloudFormation service role. Runtime deployment uses `aws cloudformation package`, a named change set, and the Discord approval boundary. SAM CLI is not required on the always-on host.
