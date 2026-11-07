#!/usr/bin/env bash
set -euo pipefail

android_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
kotlin_home="/Applications/Android Studio.app/Contents/plugins/Kotlin/kotlinc"
junit_jar="/Applications/Android Studio.app/Contents/lib/junit4.jar"

[[ -x "$kotlin_home/bin/kotlinc" ]] || { printf 'Android Studio Kotlin compiler not found.\n' >&2; exit 1; }
[[ -f "$junit_jar" ]] || { printf 'Android Studio JUnit runtime not found.\n' >&2; exit 1; }

test_dir="$(mktemp -d)"
cleanup() { rm -rf -- "$test_dir"; }
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
test_jar="$test_dir/social-drive-core-tests.jar"

classpath="$kotlin_home/lib/kotlin-test.jar:$kotlin_home/lib/kotlin-test-junit.jar:$kotlin_home/lib/kotlinx-coroutines-core-jvm.jar:$junit_jar"
"$kotlin_home/bin/kotlinc" \
  "$android_root/app/src/main/java/com/socialdrive/app/domain/Models.kt" \
  "$android_root/app/src/main/java/com/socialdrive/app/domain/IntegrationContracts.kt" \
  "$android_root/app/src/main/java/com/socialdrive/app/domain/RoutePrivacy.kt" \
  "$android_root/app/src/main/java/com/socialdrive/app/domain/ConflictReview.kt" \
  "$android_root/app/src/main/java/com/socialdrive/app/domain/DeleteLifecycle.kt" \
  "$android_root/app/src/main/java/com/socialdrive/app/data/DraftSnapshot.kt" \
  "$android_root/app/src/main/java/com/socialdrive/app/data/remote/JsonCodec.kt" \
  "$android_root/app/src/main/java/com/socialdrive/app/data/remote/CloudDriveContracts.kt" \
  "$android_root/app/src/main/java/com/socialdrive/app/data/remote/CloudDriveClient.kt" \
  "$android_root/app/src/main/java/com/socialdrive/app/data/remote/RemoteDriveApi.kt" \
  "$android_root/app/src/main/java/com/socialdrive/app/data/remote/RemoteDeleteApi.kt" \
  "$android_root/app/src/main/java/com/socialdrive/app/data/remote/RemoteConflictReviewApi.kt" \
  "$android_root/app/src/main/java/com/socialdrive/app/data/remote/CloudRemoteDriveApi.kt" \
  "$android_root/app/src/main/java/com/socialdrive/app/data/work/PublishRecoveryPolicy.kt" \
  "$android_root/app/src/main/java/com/socialdrive/app/data/work/DeleteCancellationGuard.kt" \
  "$android_root/app/src/main/java/com/socialdrive/app/safety/ParkedExecutionGate.kt" \
  "$android_root/app/src/demo/java/com/socialdrive/app/data/remote/FakeRemoteDriveApi.kt" \
  "$android_root/app/src/demo/java/com/socialdrive/app/data/remote/FakeRemoteDeleteApi.kt" \
  "$android_root/app/src/main/java/com/socialdrive/app/integration/GearMotionController.kt" \
  "$android_root/app/src/main/java/com/socialdrive/app/integration/NavigationRequestController.kt" \
  "$android_root/app/src/main/java/com/socialdrive/app/integration/NavigationRuntimeEvidence.kt" \
  "$android_root/app/src/main/java/com/socialdrive/app/integration/NavigationGatewayCoordinator.kt" \
  "$android_root/app/src/test/java/com/socialdrive/app/domain/IntegrationContractsTest.kt" \
  "$android_root/app/src/test/java/com/socialdrive/app/domain/RoutePrivacyTest.kt" \
  "$android_root/app/src/test/java/com/socialdrive/app/domain/ConflictReviewTest.kt" \
  "$android_root/app/src/test/java/com/socialdrive/app/domain/DeleteLifecycleTest.kt" \
  "$android_root/app/src/test/java/com/socialdrive/app/data/remote/CloudDriveClientTest.kt" \
  "$android_root/app/src/test/java/com/socialdrive/app/data/work/PublishRecoveryPolicyTest.kt" \
  "$android_root/app/src/test/java/com/socialdrive/app/data/work/DeleteCancellationGuardTest.kt" \
  "$android_root/app/src/test/java/com/socialdrive/app/integration/GearMotionControllerTest.kt" \
  "$android_root/app/src/testDemo/java/com/socialdrive/app/data/remote/RemoteDriveApiTest.kt" \
  "$android_root/app/src/test/java/com/socialdrive/app/integration/NavigationRequestControllerTest.kt" \
  "$android_root/app/src/test/java/com/socialdrive/app/integration/NavigationGatewayCoordinatorTest.kt" \
  "$android_root/tests/kotlin/CoreTestRunner.kt" \
  -classpath "$classpath" \
  -d "$test_jar"

java -cp "$test_jar:$kotlin_home/lib/kotlin-stdlib.jar:$classpath" CoreTestRunnerKt
