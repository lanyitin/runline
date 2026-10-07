plugins { alias(libs.plugins.kotlin.jvm) }

kotlin { jvmToolchain(25) }

// 開發入口：只依賴 core、runner、analyzer。不得依賴 Engine、Ktor、OpenTelemetry 或 Engine 自己的資料庫存取（遷移、連線池）；本機的
// jdbc-pool 資源經 accessors 帶著資料庫設定檔的驅動（WI-48）。
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
