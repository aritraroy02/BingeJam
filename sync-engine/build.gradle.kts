import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure-Kotlin sync logic. No Android dependencies, so the unit tests run on a plain JVM.
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
    api(project(":core"))
    testImplementation(libs.junit)
}
