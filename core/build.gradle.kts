import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// :core holds shared contracts, models and constants only. Plain Kotlin, no Android.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    // Flow / StateFlow appear in the contracts.
    api(libs.kotlinx.coroutines.core)
}
