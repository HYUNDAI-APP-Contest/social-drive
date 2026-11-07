import java.net.URI

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.android.compose.screenshot")
}

val releaseSigningEnvironment = listOf(
    "SOCIAL_DRIVE_RELEASE_STORE_FILE",
    "SOCIAL_DRIVE_RELEASE_STORE_PASSWORD",
    "SOCIAL_DRIVE_RELEASE_KEY_ALIAS",
    "SOCIAL_DRIVE_RELEASE_KEY_PASSWORD",
    "SOCIAL_DRIVE_RELEASE_CERT_SHA256",
)

val releaseSigningValues = releaseSigningEnvironment.associateWith { name ->
    System.getenv(name)?.takeIf { it.isNotBlank() }
}
val approvedReleaseSigning = releaseSigningValues
    .takeIf { values -> values.values.all { it != null } }
    ?.mapValues { (_, value) -> value!! }

fun quotedBuildConfig(value: String): String = "\"" +
    value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val cloudApiBaseUrl = providers.gradleProperty("socialDrive.cloud.apiBaseUrl").orElse("")
val cloudUploadHosts = providers.gradleProperty("socialDrive.cloud.uploadHosts").orElse("")
val cloudConsentPolicyVersion = providers.gradleProperty("socialDrive.cloud.consentPolicyVersion").orElse("")
val cloudCognitoRegion = providers.gradleProperty("socialDrive.cloud.cognitoRegion").orElse("")
val cloudCognitoUserPoolId = providers.gradleProperty("socialDrive.cloud.cognitoUserPoolId").orElse("")
val cloudCognitoClientId = providers.gradleProperty("socialDrive.cloud.cognitoClientId").orElse("")

tasks.register("verifyPleosCloudConfiguration") {
    group = "verification"
    description = "Validates the public AWS/Cognito configuration without printing its values."
    doLast {
        val values = linkedMapOf(
            "apiBaseUrl" to cloudApiBaseUrl.get(),
            "uploadHosts" to cloudUploadHosts.get(),
            "consentPolicyVersion" to cloudConsentPolicyVersion.get(),
            "cognitoRegion" to cloudCognitoRegion.get(),
            "cognitoUserPoolId" to cloudCognitoUserPoolId.get(),
            "cognitoClientId" to cloudCognitoClientId.get(),
        )
        val missing = values.filterValues { it.isBlank() }.keys
        if (missing.isNotEmpty()) {
            throw GradleException("PLEOS cloud configuration is incomplete: ${missing.joinToString()} are required.")
        }
        val apiUri = runCatching { URI(values.getValue("apiBaseUrl")) }.getOrNull()
        if (apiUri == null || !apiUri.scheme.equals("https", true) || apiUri.host == null ||
            apiUri.userInfo != null || apiUri.query != null || apiUri.fragment != null ||
            (apiUri.port != -1 && apiUri.port != 443)
        ) {
            throw GradleException("PLEOS cloud API URL must be an origin/path HTTPS URL without credentials, query, or fragment.")
        }
        val hosts = values.getValue("uploadHosts").split(',').map(String::trim).filter(String::isNotEmpty)
        if (hosts.isEmpty() || hosts.any { it != it.lowercase() || !it.matches(Regex("[a-z0-9.-]+")) }) {
            throw GradleException("PLEOS cloud upload hosts must be lowercase host names without schemes or paths.")
        }
        val policy = values.getValue("consentPolicyVersion")
        if (!policy.matches(Regex("[A-Za-z0-9._-]{1,32}"))) {
            throw GradleException("PLEOS cloud consent policy version has an invalid format.")
        }
        val region = values.getValue("cognitoRegion").trim().lowercase()
        val pool = values.getValue("cognitoUserPoolId").trim()
        val client = values.getValue("cognitoClientId").trim().lowercase()
        val regionPattern = Regex("[a-z]{2}(?:-gov)?-[a-z]+-\\d")
        val poolPattern = Regex("(${regionPattern.pattern})_[A-Za-z0-9]{9}")
        val poolRegion = poolPattern.matchEntire(pool)?.groupValues?.get(1)
        if (!regionPattern.matches(region) || poolRegion != region || !client.matches(Regex("[a-z0-9]{26}"))) {
            throw GradleException("PLEOS Cognito region, User Pool ID, or public mobile Client ID has an invalid format.")
        }
        logger.lifecycle("PLEOS cloud public configuration verified.")
    }
}

// Inspect the resolved graph, not the user-supplied task spelling. This catches Gradle
// abbreviations and lifecycle tasks whose dependencies create a release artifact.
gradle.taskGraph.whenReady {
    val releaseArtifactTasks = allTasks.filter { task ->
        task.project == project &&
            (task.name.matches(Regex("^(assemble|bundle|package|install).*Release$")) ||
                task.name.matches(Regex("^(package|sign).*ReleaseBundle$")) ||
                task.name.matches(Regex("^publish.*Release(?:$|[A-Z].*)")))
    }
    if (releaseArtifactTasks.isNotEmpty()) {
        val missing = releaseSigningValues.filterValues { it == null }.keys
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Release signing environment is incomplete: ${missing.sorted().joinToString()} are required."
            )
        }
        val values = approvedReleaseSigning
            ?: throw GradleException("Release signing environment is incomplete.")
        val store = file(values.getValue("SOCIAL_DRIVE_RELEASE_STORE_FILE")).canonicalFile
        val repository = rootProject.projectDir.parentFile.canonicalFile
        if (!store.isFile) {
            throw GradleException("Release keystore must be an existing regular file.")
        }
        if (store.toPath().startsWith(repository.toPath())) {
            throw GradleException("Release keystore must be outside the repository.")
        }
        val fingerprint = values.getValue("SOCIAL_DRIVE_RELEASE_CERT_SHA256")
            .replace(Regex("[:\\s]"), "")
        if (!fingerprint.matches(Regex("[0-9A-Fa-f]{64}"))) {
            throw GradleException("Approved release certificate SHA-256 must contain exactly 64 hexadecimal digits.")
        }
    }
}

android {
    namespace = "com.socialdrive.app"
    compileSdk = 35
    experimentalProperties["android.experimental.enableScreenshotTest"] = true

    defaultConfig {
        applicationId = "com.socialdrive.app"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "com.socialdrive.app.SocialDriveTestRunner"
    }

    flavorDimensions += "integration"
    productFlavors {
        create("demo") {
            dimension = "integration"
            applicationIdSuffix = ".demo"
            versionNameSuffix = "-demo"
            buildConfigField("String", "INTEGRATION_MODE", "\"demo\"")
        }
        create("pleos") {
            dimension = "integration"
            buildConfigField("String", "INTEGRATION_MODE", "\"pleos\"")
            buildConfigField("String", "CLOUD_API_BASE_URL", quotedBuildConfig(cloudApiBaseUrl.get()))
            buildConfigField("String", "CLOUD_UPLOAD_HOSTS", quotedBuildConfig(cloudUploadHosts.get()))
            buildConfigField("String", "CLOUD_CONSENT_POLICY_VERSION", quotedBuildConfig(cloudConsentPolicyVersion.get()))
            buildConfigField("String", "CLOUD_COGNITO_REGION", quotedBuildConfig(cloudCognitoRegion.get()))
            buildConfigField("String", "CLOUD_COGNITO_USER_POOL_ID", quotedBuildConfig(cloudCognitoUserPoolId.get()))
            buildConfigField("String", "CLOUD_COGNITO_CLIENT_ID", quotedBuildConfig(cloudCognitoClientId.get()))
        }
    }

    signingConfigs {
        if (approvedReleaseSigning != null) {
            create("approvedRelease") {
                storeFile = file(approvedReleaseSigning.getValue("SOCIAL_DRIVE_RELEASE_STORE_FILE"))
                storePassword = approvedReleaseSigning.getValue("SOCIAL_DRIVE_RELEASE_STORE_PASSWORD")
                keyAlias = approvedReleaseSigning.getValue("SOCIAL_DRIVE_RELEASE_KEY_ALIAS")
                keyPassword = approvedReleaseSigning.getValue("SOCIAL_DRIVE_RELEASE_KEY_PASSWORD")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = approvedReleaseSigning?.let { signingConfigs.getByName("approvedRelease") }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            if (it.name.startsWith("testDemo")) {
                it.systemProperty("user.home", rootProject.file(".gradle/local-runtime").absolutePath)
            }
        }
    }

    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }
}

afterEvaluate {
    listOf("Demo", "Pleos").forEach { flavor ->
        tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compile${flavor}DebugScreenshotTestKotlin") {
            compilerOptions.freeCompilerArgs.add("-Xskip-metadata-version-check")
        }
    }

    tasks.named("connectedPleosDebugAndroidTest") {
        doFirst {
            val allowReset = providers.gradleProperty("socialDrive.tests.allowAppDataReset")
                .map { it.toBooleanStrictOrNull() == true }
                .getOrElse(false)
            if (allowReset) return@doFirst

            val adb = android.sdkDirectory.resolve("platform-tools/adb")
            if (!adb.isFile) return@doFirst
            val deviceRows = providers.exec {
                commandLine(adb, "devices")
            }.standardOutput.asText.get().lineSequence()
                .map(String::trim)
                .filter { it.endsWith("\tdevice") }
                .toList()
            if (deviceRows.size != 1) {
                throw GradleException(
                    "PLEOS instrumentation safety check requires exactly one connected device; " +
                        "the test runner may uninstall the target app and erase its data."
                )
            }
            val serial = deviceRows.single().substringBefore('\t')
            val currentUser = providers.exec {
                commandLine(adb, "-s", serial, "shell", "am", "get-current-user")
            }.standardOutput.asText.get().trim()
            val installed = providers.exec {
                isIgnoreExitValue = true
                commandLine(adb, "-s", serial, "shell", "pm", "path", "--user", currentUser, "com.socialdrive.app")
            }.standardOutput.asText.get().trim().startsWith("package:")
            if (installed) {
                throw GradleException(
                    "Refusing connectedPleosDebugAndroidTest because com.socialdrive.app is installed for " +
                        "user $currentUser. Android instrumentation can uninstall it and erase app data. " +
                        "Use a disposable AVD, or explicitly pass " +
                        "-PsocialDrive.tests.allowAppDataReset=true after accepting the reset."
                )
            }
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
    arg("room.generateKotlin", "true")
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.3")
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    "pleosImplementation"("ai.pleos.playground:Vehicle:2.3.2")
    "pleosImplementation"("ai.pleos.playground:NaviHelper:3.0.5")
    "pleosImplementation"("ai.pleos.playground:FusedLocation:1.1.3")
    "pleosImplementation"("ai.pleos.playground:TextToSpeech:3.3.1")
    "pleosImplementation"("ai.pleos.playground:SpeechToText:3.3.1")
    "pleosImplementation"("com.amplifyframework:aws-auth-cognito:2.27.4")
    "pleosImplementation"("androidx.browser:browser:1.8.0") {
        version { strictly("1.8.0") }
        because("PLEOS toolchain is pinned to compileSdk 35; Hosted UI is not used by the SRP adapter")
    }
    ksp("androidx.room:room-compiler:2.6.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test:2.0.21")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    "testDemoImplementation"("org.robolectric:robolectric:4.14.1")
    "testDemoImplementation"(composeBom)
    "testDemoImplementation"("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.room:room-testing:2.6.1")
    androidTestImplementation("androidx.sqlite:sqlite-framework:2.4.0")
    androidTestImplementation("androidx.work:work-testing:2.9.1")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    screenshotTestImplementation("com.android.tools.screenshot:screenshot-validation-api:0.0.1-alpha16") {
        isTransitive = false
    }
    screenshotTestImplementation("androidx.compose.ui:ui-tooling")
}
