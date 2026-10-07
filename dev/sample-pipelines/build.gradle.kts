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
val scenarios = listOf("slow", "failing", "unsafe", "resource", "typed", "usage")

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

// For the security check of the Console (WI-37), not for a demo: pipelines whose every string is
// markup or a script (`adversarial.jar`), one whose name the Engine refuses
// (`adversarial-name.jar`) and one whose jar has an unreadable class that carries markup
// (`adversarial-unreadable.jar`). Not named demo-*, so dev.sh does not list them.
val adversarialJar =
    tasks.register<Jar>("adversarialJar") {
      group = "build"
      description = "Packages the pipeline with hostile strings (without core)"
      archiveBaseName = "adversarial"
      archiveVersion = ""
      destinationDirectory = layout.buildDirectory.dir("pipelines")
      from(sourceSets.main.get().output) { include("samples/adversarial/Hostile*") }
    }

val adversarialNameJar =
    tasks.register<Jar>("adversarialNameJar") {
      group = "build"
      description = "Packages the pipeline whose name the Engine refuses and says back"
      archiveBaseName = "adversarial-name"
      archiveVersion = ""
      destinationDirectory = layout.buildDirectory.dir("pipelines")
      from(sourceSets.main.get().output) { include("samples/adversarial/RejectedNamePipeline*") }
    }

val adversarialUnreadableJar =
    tasks.register<Jar>("adversarialUnreadableJar") {
      group = "build"
      description = "Packages the pipeline whose jar has an unreadable class named like markup"
      archiveBaseName = "adversarial-unreadable"
      archiveVersion = ""
      destinationDirectory = layout.buildDirectory.dir("pipelines")
      from(sourceSets.main.get().output) {
        include("samples/adversarial/UnreadablePipeline*")
        // Not the helper class: bogus.bin takes its place below.
        exclude("samples/adversarial/UnreadableHelper*")
      }
      from("src/hostile-entry/bogus.bin") {
        into("samples/adversarial")
        rename { "UnreadableHelper id=\"pwn-class\" onerror=pwned(1).class" }
      }
    }

tasks.named("pipelineJars") {
  dependsOn(adversarialJar, adversarialNameJar, adversarialUnreadableJar)
}
