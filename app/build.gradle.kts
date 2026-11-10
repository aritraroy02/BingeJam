import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    // Needs app/google-services.json. See RUN_ON_MY_MACHINE.md, "Firebase setup".
    alias(libs.plugins.google.services)
}

// Release signing comes from keystore.properties (git-ignored; see RUN_ON_MY_MACHINE.md).
// Without it, release builds are unsigned and only debug builds can be installed.
val keystoreProps = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { file ->
    Properties().apply { file.inputStream().use { load(it) } }
}

android {
    namespace = "com.syncit.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.syncit.app"
        minSdk = 26
        // Latest stable at authoring time; bump if a newer stable API level is out.
        targetSdk = 36
        versionCode = 19
        versionName = "0.7.0"
        // Updates come only from the Indus Appstore (user's decision, 2026-11-04): no GitHub update
        // check, banner or "new version" notification. -Pstore=github brings the old updater back.
        val store = (findProperty("store") as String?) ?: "indus"
        buildConfigField("boolean", "SELF_UPDATE", (store == "github").toString())
    }

    signingConfigs {
        if (keystoreProps != null) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8 roughly thirds the APK, which matters for downloads from the invite page.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":sync-engine"))
    implementation(project(":media"))
    implementation(project(":data-firebase"))
    implementation(project(":voice"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Real backdrop blur for the liquid-glass UI (Android 12+; translucent scrim below).
    implementation(libs.haze)

    // Google sign-in: Credential Manager returns a Google ID token that AuthService exchanges.
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)

    // Push notifications for messages and friend requests (sent by push-relay/).
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    // Profile pictures (still and animated GIF) from push-relay/ and Google.
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.coil.network.okhttp)
    // QR code for personal invite links (encoding only, no camera).
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
}
