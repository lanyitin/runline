plugins { alias(libs.plugins.kotlin.jvm) }

kotlin { jvmToolchain(25) }

// 開發入口：只依賴 core、runner、analyzer。不得依賴 Engine、Ktor、OpenTelemetry 或資料庫。
dependencies {
  implementation(project(":accessors"))
  implementation(project(":analyzer"))
  implementation(project(":core"))
  implementation(project(":runner"))
  // The settings file of an `openai-compatible` resource is JSON, as the administrator's is.
  implementation(libs.kotlinx.serialization.json)

  testImplementation(kotlin("test"))
  testImplementation(testFixtures(project(":accessors")))
}

tasks.test { useJUnitPlatform() }
