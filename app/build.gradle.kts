import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
}

// AWS の設定 (リージョン, ID プール ID, テーブル名, 機器) は, リポジトリに入れない.
// local.properties (コミットしない. UTF-8. 表示名に日本語を書ける) か, Gradle のプロパティ (-Pthermon.table=...) で渡す.
val localProperties =
    Properties().apply {
        val file = rootProject.file("local.properties")
        if (file.exists()) {
            file.reader(Charsets.UTF_8).use { load(it) }
        }
    }

/** 設定の値を, local.properties, Gradle のプロパティ, 既定値の順に探す. */
fun configValue(
    name: String,
    default: String,
): String = localProperties.getProperty(name) ?: providers.gradleProperty(name).orNull ?: default

/** BuildConfig に書く, 文字列の値 (引用符を付ける). */
fun configString(
    name: String,
    default: String,
): String = "\"" + configValue(name, default).replace("\\", "\\\\").replace("\"", "\\\"") + "\""

// Kotlin の警告が出たら, ビルドを失敗させる (core と同じ)
kotlin {
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

android {
    namespace = "jp.thermon.app"
    // compileSdk 37 は, AWS SDK for Kotlin が使う OkHttp 5.5 の要求. build-tools は, Docker のイメージに
    // 入っている版に固定する (SDK は読み取り専用で, 既定の版 (36.0.0) は, 入っていない)
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        // applicationId は, 仮の値 (リリースの前に決める)
        applicationId = "jp.thermon.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        // AWS の設定 (README / SETUP.md の「AWS への接続」を参照)
        buildConfigField("String", "AWS_REGION", configString("thermon.region", "ap-northeast-1"))
        buildConfigField("String", "IDENTITY_POOL_ID", configString("thermon.identityPoolId", ""))
        buildConfigField("String", "TABLE_NAME", configString("thermon.table", "thermo-readings"))
        buildConfigField("String", "NODES", configString("thermon.nodes", ""))
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.all {
            it.useJUnitPlatform()
        }
    }

    // 警告も, エラーにする
    lint {
        warningsAsErrors = true
        abortOnError = true
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.work)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
    testRuntimeOnly(libs.junit.launcher)
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
}
