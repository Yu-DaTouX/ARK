import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.ark"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.ark.offline"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "0.3.0"
        // 只打 64 位 ARM：现在的手机都是，安装包也小一半
        ndk { abiFilters += "arm64-v8a" }
    }

    // 发布签名：环境变量 ARK_KEYSTORE_PROPS 指向的 properties 文件（storeFile / storePassword / keyAlias / keyPassword），
    // 密钥放在仓库外。没有就用调试签名（自己编译自用没问题，但不能覆盖安装官方 Release 版）
    val ksProps = System.getenv("ARK_KEYSTORE_PROPS")?.let { file(it) }?.takeIf { it.exists() }
        ?.let { f -> Properties().also { p -> f.inputStream().use { p.load(it) } } }
    signingConfigs {
        if (ksProps != null) create("release") {
            storeFile = file(ksProps.getProperty("storeFile"))
            storePassword = ksProps.getProperty("storePassword")
            keyAlias = ksProps.getProperty("keyAlias")
            keyPassword = ksProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (ksProps != null) "release" else "debug")
        }
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // 资料包是 SQLite 文件，首次启动从 assets 复制出来，不压缩可以省掉解压
    androidResources { noCompress += listOf("arkpack", "pmtiles") }

    packaging { jniLibs { useLegacyPackaging = false } }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core:1.7.8")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // 自带 SQLite（编译时开了 FTS5），不依赖系统 SQLite 的版本
    implementation("androidx.sqlite:sqlite-bundled:2.7.1")
    // 端侧大模型推理（GPU / CPU）
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.19.1")
    // 离线地图：地球仪投影版（BSD-2），读本地 PMTiles
    implementation("org.maplibre.gl:android-sdk:2026.11.2-globe")
    // 二维码生成（只用 core，约 0.5 MB）：离线分享时显示下载网址
    implementation("com.google.zxing:core:3.5.3")
}
