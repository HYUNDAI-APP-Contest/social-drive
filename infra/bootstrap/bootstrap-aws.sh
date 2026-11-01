#!/usr/bin/env bash
set -euo pipefail

deploy_region="ap-northeast-2"
service_role="SocialDriveCloudFormationServiceRole"
inline_policy="SocialDriveCloudFormationProvisioningPolicy"
account_id="$(aws sts get-caller-identity --query Account --output text)"
artifact_bucket="social-drive-sam-artifacts-${account_id}-${deploy_region}"
bootstrap_dir="$(mktemp -d)"
trap 'rm -rf "$bootstrap_dir"' EXIT

cat > "$bootstrap_dir/trust-policy.json" <<'JSON'
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": { "Service": "cloudformation.amazonaws.com" },
      "Action": "sts:AssumeRole"
    }
  ]
}
JSON

cat > "$bootstrap_dir/service-policy.json" <<'JSON'
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "ManageSocialDriveLambda",
      "Effect": "Allow",
      "Action": "lambda:*",
      "Resource": "arn:aws:lambda:ap-northeast-2:*:function:social-drive-*"
    },
    {
      "Sid": "ManageApiGatewayInSeoul",
      "Effect": "Allow",
      "Action": "apigateway:*",
      "Resource": "arn:aws:apigateway:ap-northeast-2::*"
    },
    {
      "Sid": "ManageCognitoInSeoul",
      "Effect": "Allow",
      "Action": "cognito-idp:*",
      "Resource": "arn:aws:cognito-idp:ap-northeast-2:*:userpool/*"
    },
    {
      "Sid": "CreateCognitoResources",
      "Effect": "Allow",
      "Action": "cognito-idp:CreateUserPool",
      "Resource": "*"
    },
    {
      "Sid": "ManageSocialDriveDynamoDB",
      "Effect": "Allow",
      "Action": "dynamodb:*",
      "Resource": [
        "arn:aws:dynamodb:ap-northeast-2:*:table/social-drive-*",
        "arn:aws:dynamodb:ap-northeast-2:*:table/social-drive-*/index/*"
      ]
    },
    {
      "Sid": "CreateSocialDriveBuckets",
      "Effect": "Allow",
      "Action": "s3:CreateBucket",
      "Resource": "*",
      "Condition": {
        "StringEquals": { "s3:LocationConstraint": "ap-northeast-2" }
      }
    },
    {
      "Sid": "ManageSocialDriveBuckets",
      "Effect": "Allow",
      "Action": "s3:*",
      "Resource": [
        "arn:aws:s3:::social-drive-*",
        "arn:aws:s3:::social-drive-*/*"
      ]
    },
    {
      "Sid": "ManageSocialDriveLogGroups",
      "Effect": "Allow",
      "Action": "logs:*",
      "Resource": [
        "arn:aws:logs:ap-northeast-2:*:log-group:/aws/lambda/social-drive-*",
        "arn:aws:logs:ap-northeast-2:*:log-group:/aws/apigateway/social-drive-*"
      ]
    },
    {
      "Sid": "ManageSocialDriveAlarms",
      "Effect": "Allow",
      "Action": "cloudwatch:*",
      "Resource": "arn:aws:cloudwatch:ap-northeast-2:*:alarm:social-drive-*"
    },
    {
      "Sid": "ReadDeploymentObservability",
      "Effect": "Allow",
      "Action": [
        "cloudwatch:DescribeAlarms",
        "logs:DescribeLogGroups"
      ],
      "Resource": "*"
    },
    {
      "Sid": "ManageGeneratedLambdaRoles",
      "Effect": "Allow",
      "Action": [
        "iam:CreateRole",
        "iam:DeleteRole",
        "iam:GetRole",
        "iam:TagRole",
        "iam:UntagRole",
        "iam:UpdateAssumeRolePolicy",
        "iam:PutRolePolicy",
        "iam:GetRolePolicy",
        "iam:DeleteRolePolicy",
        "iam:AttachRolePolicy",
        "iam:DetachRolePolicy",
        "iam:ListRolePolicies",
        "iam:ListAttachedRolePolicies"
      ],
      "Resource": "arn:aws:iam::*:role/social-drive-*"
    },
    {
      "Sid": "PassGeneratedRolesOnlyToLambda",
      "Effect": "Allow",
      "Action": "iam:PassRole",
      "Resource": "arn:aws:iam::*:role/social-drive-*",
      "Condition": {
        "StringEquals": { "iam:PassedToService": "lambda.amazonaws.com" }
      }
    }
  ]
}
JSON

if aws iam get-role --role-name "$service_role" >/dev/null 2>&1; then
  aws iam update-assume-role-policy \
    --role-name "$service_role" \
    --policy-document "file://$bootstrap_dir/trust-policy.json"
else
  aws iam create-role \
    --role-name "$service_role" \
    --assume-role-policy-document "file://$bootstrap_dir/trust-policy.json" \
    --description "Provision only Social Drive dev resources through CloudFormation" \
    --tags Key=Project,Value=social-drive Key=Environment,Value=dev >/dev/null
fi

aws iam put-role-policy \
  --role-name "$service_role" \
  --policy-name "$inline_policy" \
  --policy-document "file://$bootstrap_dir/service-policy.json"

if ! aws s3api head-bucket --bucket "$artifact_bucket" >/dev/null 2>&1; then
  aws s3api create-bucket \
    --bucket "$artifact_bucket" \
    --region "$deploy_region" \
    --create-bucket-configuration "LocationConstraint=$deploy_region" >/dev/null
fi

aws s3api put-public-access-block \
  --bucket "$artifact_bucket" \
  --public-access-block-configuration \
    BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true

aws s3api put-bucket-encryption \
  --bucket "$artifact_bucket" \
  --server-side-encryption-configuration \
    '{"Rules":[{"ApplyServerSideEncryptionByDefault":{"SSEAlgorithm":"AES256"}}]}'

aws s3api put-bucket-versioning \
  --bucket "$artifact_bucket" \
  --versioning-configuration Status=Enabled

aws s3api put-bucket-lifecycle-configuration \
  --bucket "$artifact_bucket" \
  --lifecycle-configuration \
    '{"Rules":[{"ID":"cleanup-deployment-artifacts","Status":"Enabled","Filter":{},"AbortIncompleteMultipartUpload":{"DaysAfterInitiation":1},"NoncurrentVersionExpiration":{"NoncurrentDays":30}}]}'

aws s3api put-bucket-tagging \
  --bucket "$artifact_bucket" \
  --tagging 'TagSet=[{Key=Project,Value=social-drive},{Key=Environment,Value=dev},{Key=Purpose,Value=sam-artifacts}]'

echo "AWS bootstrap complete."
echo "CloudFormation service role: ready"
echo "Private SAM artifact bucket: ready"
