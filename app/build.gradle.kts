import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.isFile) {
        file.reader(Charsets.UTF_8).use(::load)
    }
}

fun signingProperty(name: String): String? {
    return localProperties.getProperty(name)
        ?: providers.gradleProperty(name).orNull
        ?: providers.environmentVariable(name).orNull
}

val releaseStoreFile = signingProperty("release.storeFile")
val releaseStorePassword = signingProperty("release.storePassword")
val releaseKeyAlias = signingProperty("release.keyAlias")
val releaseKeyPassword = signingProperty("release.keyPassword")
val hasReleaseSigning = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword
).all { !it.isNullOrBlank() }

android {
    namespace = "com.xdreamllc.oplus"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.xdreamllc.oplus"
        minSdk = 33
        targetSdk = 36
        versionCode = 3
        versionName = "2.0.0"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Modern Xposed API - provided by LSPosed/libxposed at runtime.
    compileOnly("io.github.libxposed:api:101.0.1")

    // Modern Xposed Service - app side bridge to talk to the framework
    // (RemotePreferences, framework version, scope queries, etc.).
    implementation("io.github.libxposed:service:101.0.0")

    // AndroidX + Compose
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material3:material3")
}
