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
            signingConfig = approvedReleaseSigning?.let { signingConfigs.getByName("approvedRelease") }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
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
    tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileDemoDebugScreenshotTestKotlin") {
        kotlinOptions.freeCompilerArgs += "-Xskip-metadata-version-check"
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
    arg("room.generateKotlin", "true")
}

dependencies {
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
    "pleosImplementation"("ai.pleos.playground:Vehicle:2.3.2")
    "pleosImplementation"("ai.pleos.playground:NaviHelper:3.0.5")
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
