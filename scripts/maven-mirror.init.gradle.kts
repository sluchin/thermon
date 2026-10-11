// Maven Central の, Google のミラーを, 先に参照する Gradle の初期化スクリプト.
// クラウドのセッションでは, 共有の出口の IP から, Maven Central が 429 (Too Many Requests) を返すため.
// scripts/cloud-docker.sh の run が, GRADLE_HOME_DIR の init.d に置く. ローカルや CI では, 使わない.
// ミラーにない成果物 (404) は, google() や mavenCentral() が引き受ける.
beforeSettings {
val mirror = "https://maven-central.storage-download.googleapis.com/maven2/"
pluginManagement.repositories.maven { url = uri(mirror) }
pluginManagement.repositories.google()
// Plugin Portal にしかないプラグイン (ktlint など) は, Maven Central が 429 を返すと, 最後の Portal まで進めない.
pluginManagement.repositories.maven { url = uri("https://plugins.gradle.org/m2/") }
dependencyResolutionManagement.repositories.maven { url = uri(mirror) }
}
