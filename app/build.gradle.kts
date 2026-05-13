plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.shizulu.manager"
    compileSdk = 36

    val gitSha = providers.exec {
        commandLine("git", "rev-parse", "--short=12", "HEAD")
    }.standardOutput.asText.map { it.trim() }.orElse("unknown")
    val gitCommitCount = providers.exec {
        commandLine("git", "rev-list", "--count", "HEAD")
    }.standardOutput.asText.map { it.trim().toInt() }.orElse(1)

    defaultConfig {
        applicationId = "com.shizulu.manager"
        minSdk = 24
        targetSdk = 35
        val buildNumber = gitCommitCount.get()
        versionCode = buildNumber
        versionName = "0.1.$buildNumber"
        buildConfigField("String", "GIT_SHA", "\"${gitSha.get()}\"")
    }

    buildFeatures {
        aidl = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/versions/**/OSGI-INF/MANIFEST.MF",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE.md",
                "META-INF/LICENSE-notice.md"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("com.flyfishxu:kadb:2.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.core:core:1.16.0")
    implementation("androidx.annotation:annotation:1.9.1")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
}
