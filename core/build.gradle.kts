plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
    alias(libs.plugins.dokka)
    jacoco
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        // 警告が出たら, ビルドを失敗させる
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    // AWS のクライアントの型は, app も使う (api). ネットワークは, SDK の既定の HTTP エンジンが行う
    api(libs.aws.cognitoidentity)
    api(libs.aws.dynamodb)
    api(libs.coroutines.core)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.test {
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
}

jacoco {
    toolVersion = libs.versions.jacoco.get()
}

tasks.jacocoTestReport {
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

// core の行と分岐のカバレッジは, 100% (UI は app にあり, 対象外)
tasks.jacocoTestCoverageVerification {
    violationRules {
        rule {
            limit {
                counter = "LINE"
                minimum = "1.0".toBigDecimal()
            }
            limit {
                counter = "BRANCH"
                minimum = "1.0".toBigDecimal()
            }
        }
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
}

// ドキュメントがないものは, 警告にして, 警告があれば失敗する
dokka {
    dokkaSourceSets.configureEach {
        reportUndocumented.set(true)
    }
    dokkaPublications.html {
        failOnWarning.set(true)
    }
}
