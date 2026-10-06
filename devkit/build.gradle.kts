plugins { alias(libs.plugins.kotlin.jvm) }

kotlin { jvmToolchain(25) }

// 開發入口：只依賴 core、runner、analyzer。不得依賴 Engine、Ktor、OpenTelemetry 或資料庫。
dependencies {
  implementation(project(":analyzer"))
  implementation(project(":core"))
  implementation(project(":runner"))

  testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }
