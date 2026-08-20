import java.util.Properties

plugins {
    // AGP 9.x embeds Kotlin compilation — do NOT apply org.jetbrains.kotlin.android
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

val releaseSigningPropertiesPath = providers.gradleProperty("releaseSigningProperties")
    .orElse("keystore.properties")
    .get()
val releaseSigningPropertiesFile = rootProject.file(releaseSigningPropertiesPath)
val releaseSigningProperties = Properties().apply {
    if (releaseSigningPropertiesFile.isFile) {
        releaseSigningPropertiesFile.inputStream().use(::load)
    }
}
val releaseSigningPropertyNames = listOf(
    "storeFile",
    "storePassword",
    "keyAlias",
    "keyPassword",
)
val missingReleaseSigningProperties = releaseSigningPropertyNames.filter {
    releaseSigningProperties.getProperty(it).isNullOrBlank()
}
val releaseStoreFile = releaseSigningProperties.getProperty("storeFile")
    ?.takeIf { it.isNotBlank() }
    ?.let(rootProject::file)
val releaseSigningIssues = buildList {
    addAll(missingReleaseSigningProperties)
    if (missingReleaseSigningProperties.isEmpty() && releaseStoreFile?.isFile != true) {
        add("storeFile (file not found)")
    }
}
val releaseSigningConfigured = releaseSigningIssues.isEmpty()

fun releaseSigningError(): String = buildString {
    appendLine("Release signing is not configured.")
    appendLine("Copy keystore.properties.example to keystore.properties and fill in its values.")
    appendLine("The following signing inputs are missing or invalid: ${releaseSigningIssues.joinToString()}")
    append("Keep keystore.properties and the private keystore outside version control.")
}

android {
    namespace = "com.johnc4rl0.smsforwarder"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.johnc4rl0.smsforwarder"
        minSdk = 31
        targetSdk = 37
        versionCode = 2
        versionName = "1.0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = releaseSigningProperties.getProperty("storePassword")
                keyAlias = releaseSigningProperties.getProperty("keyAlias")
                keyPassword = releaseSigningProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

// Never let a release APK silently fall back to the debug certificate or an
// unsigned artifact. Debug/unit-test builds remain usable without local
// release credentials; packaging a release requires an explicit key.
val releasePackagingTaskNames = setOf(
    "assembleRelease",
    "bundleRelease",
    "packageRelease",
    "packageReleaseBundle",
    "packageReleaseUniversalApk",
    "signRelease",
    "signReleaseBundle",
    "signingConfigWriterRelease",
)
tasks.configureEach {
    if (name in releasePackagingTaskNames) {
        // Force the guard to run even when a stale release output is present.
        outputs.upToDateWhen { releaseSigningConfigured }
        if (releaseSigningConfigured) {
            releaseStoreFile?.let { inputs.file(it) }
        }
        doFirst {
            check(releaseSigningConfigured) { releaseSigningError() }
        }
    }
}

// AGP 9 kotlin extension (kotlinOptions {} removed)
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.biometric)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.truth)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.work.testing)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.uiautomator)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.work.testing)
}
