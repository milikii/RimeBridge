plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 自签名 keystore：keystore/rimebridge.jks（不入库）。
// 口令从不写进仓库，由 ~/.gradle/gradle.properties 或命令行 -P 提供；
// 没提供时回落到 debug 签名，保证 clone 下来直接能构建。
val ksPath = providers.gradleProperty("RB_STORE_FILE").getOrElse("keystore/rimebridge.jks")
val ksFile = rootProject.file(ksPath)
val ksPass = providers.gradleProperty("RB_STORE_PASSWORD").getOrElse("")
val keyPass = providers.gradleProperty("RB_KEY_PASSWORD").getOrElse(ksPass)

if (ksFile.exists() && ksPass.isEmpty()) {
    logger.warn(
        "RimeBridge: keystore 存在但没给 RB_STORE_PASSWORD，" +
            "release 将回落为 debug 签名（详见 gradle.properties 注释）。"
    )
}

android {
    namespace = "com.rimebridge"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.rimebridge"
        minSdk = 31
        targetSdk = 36
        versionCode = 3
        versionName = "1.2"
    }

    signingConfigs {
        if (ksFile.exists() && ksPass.isNotEmpty()) {
            create("release") {
                storeFile = ksFile
                storePassword = ksPass
                keyAlias = providers.gradleProperty("RB_KEY_ALIAS").getOrElse("rimebridge")
                keyPassword = keyPass
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        buildConfig = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    packaging {
        resources {
            excludes += setOf(
                "kotlin/**",
                "META-INF/*.kotlin_module",
                "META-INF/*.version",
                "**/*.kotlin_builtins",
                "DebugProbesKt.bin",
                "kotlin-tooling-metadata.json"
            )
        }
    }

    lint {
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // 有意留空：不引任何第三方依赖（含 AndroidX）
}
