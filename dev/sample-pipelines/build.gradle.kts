plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.ktfmt)
}

kotlin { jvmToolchain(25) }

dependencies {
  // 只在編譯期使用：Run 一律使用 Runner 提供的 core，jar 內夾帶 core 會被上傳拒絕（core_classes_bundled）。
  // Kotlin 標準函式庫同樣由 Run 的執行期提供，所以 jar 只含 pipeline 自己的類別。
  compileOnly("dev.lawlan.runline:core:1.0.0-SNAPSHOT")
}

// 每個情境一個 jar（以套件切開），方便在 Console 逐一上傳。輸出在 build/pipelines/。
val scenarios = listOf("slow", "failing", "unsafe", "resource")

val scenarioJars = scenarios.map { scenario ->
  tasks.register<Jar>("${scenario}Jar") {
    group = "build"
    description = "Packages the '$scenario' sample pipeline (without core)"
    archiveBaseName = "demo-$scenario"
    archiveVersion = ""
    destinationDirectory = layout.buildDirectory.dir("pipelines")
    from(sourceSets.main.get().output) { include("samples/$scenario/**") }
  }
}

tasks.register("pipelineJars") {
  group = "build"
  description = "Builds every sample pipeline jar into build/pipelines/"
  dependsOn(scenarioJars)
}
