// 示範 pipeline 專案：獨立的 Gradle build，不屬於 runline 根專案（根的 settings.gradle.kts 不 include 它），
// 所以不會影響既有建置。它以 composite build 取得根專案的 core，只在編譯期使用，不打包進 jar。
pluginManagement { repositories { gradlePluginPortal() } }

plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }

dependencyResolutionManagement {
  repositories { mavenCentral() }
  // 與根專案共用同一份版本目錄，Kotlin 與 ktfmt 版本只有一個來源。
  versionCatalogs { create("libs") { from(files("../../gradle/libs.versions.toml")) } }
}

rootProject.name = "sample-pipelines"

includeBuild("../..")
