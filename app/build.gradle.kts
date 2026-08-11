import org.jetbrains.kotlin.gradle.dsl.JvmTarget

val gitVersionName = providers.exec {
    commandLine("git", "describe", "--tags", "--always", "--dirty")
}.standardOutput.asText.map { it.trim().removePrefix("v") }

val gitVersionCode = providers.exec {
    commandLine("git", "rev-list", "--count", "HEAD")
}.standardOutput.asText.map { it.trim().toInt() }

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.cleancopy"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.cleancopy"
        minSdk = 26
        targetSdk = 35
        versionCode = gitVersionCode.get()
        versionName = gitVersionName.get()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        val keystorePath = System.getenv("ANDROID_KEYSTORE_FILE")
        val keystorePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
        val keystoreAlias = System.getenv("ANDROID_KEYSTORE_ALIAS")

        if (!keystorePath.isNullOrBlank() && !keystorePassword.isNullOrBlank() && !keystoreAlias.isNullOrBlank()) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = keystorePassword
                keyAlias = keystoreAlias
                keyPassword = keystorePassword
            }
        }
    }

    buildTypes {
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        baseline = file("lint-baseline.xml")
    }

    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.06.01")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.paging:paging-runtime-ktx:3.2.1")
    implementation("androidx.paging:paging-compose:3.2.1")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.media3:media3-transformer:1.3.1")
    implementation("androidx.media3:media3-effect:1.3.1")
    implementation("androidx.media3:media3-common:1.3.1")
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
