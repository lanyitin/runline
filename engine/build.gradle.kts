plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(ktorLibs.plugins.ktor)
  alias(libs.plugins.kotlin.serialization)
}

application { mainClass = "dev.lawlan.runline.engine.MainKt" }

kotlin { jvmToolchain(25) }

dependencies {
  implementation(libs.cron.utils)
  implementation(ktorLibs.serialization.kotlinx.json)
  implementation(ktorLibs.server.auth)
  implementation(ktorLibs.server.config.yaml)
  implementation(ktorLibs.server.contentNegotiation)
  implementation(ktorLibs.server.core)
  implementation(ktorLibs.server.di)
  implementation(ktorLibs.server.metrics)
  implementation(ktorLibs.server.netty)
  implementation(ktorLibs.server.routingOpenapi)
  implementation(ktorLibs.server.statusPages)
  implementation(ktorLibs.server.swagger)
  implementation(ktorLibs.server.websockets)
  implementation(libs.flyway.core)
  implementation(libs.flyway.postgresql)
  implementation(libs.logback.classic)
  implementation(libs.opentelemetry.exporterOtlp)
  implementation(libs.opentelemetry.ktorInstrumentation)
  implementation(libs.opentelemetry.sdkAutoconfigure)
  implementation(libs.opentelemetry.semconv)
  implementation(libs.postgresql)
  implementation(project(":analyzer"))
  implementation(project(":core"))
  implementation(project(":runner"))

  testImplementation(kotlin("test"))
  testImplementation(ktorLibs.server.testHost)
  testImplementation(ktorLibs.client.websockets)
  testImplementation(libs.opentelemetry.sdkTesting)
  testImplementation(libs.testcontainers.postgresql)
}

tasks.test {
  useJUnitPlatform()
  // Runs against the packaged Engine have their own task (packagedTest).
  exclude("**/packaged/**")
  // No OTLP collector runs during tests; without this, closing the SDK at application shutdown
  // waits for the exporter to time out.
  systemProperty("otel.traces.exporter", "none")
  systemProperty("otel.logs.exporter", "none")
  // The API documentation is checked against the routes (ApiDocumentationTest).
  systemProperty(
      "runline.apiDoc",
      rootProject.file("docs/stable/pipeline-engine/08-api.md").absolutePath,
  )
  systemProperty("runline.engineSources", file("src/main/kotlin").absolutePath)
  // The configuration described in the README is checked against the code
  // (RetentionDocumentationTest).
  systemProperty("runline.readme", rootProject.file("README.md").absolutePath)
  // Declared as inputs so that editing the document alone reruns that check.
  inputs.file(rootProject.file("docs/stable/pipeline-engine/08-api.md")).withPropertyName("apiDoc")
  inputs.dir(file("src/main/kotlin")).withPropertyName("engineSources")
  inputs.file(rootProject.file("README.md")).withPropertyName("readme")
}

// One-off admin process: applies database migrations with the Engine's own code and configuration.
tasks.register<JavaExec>("migrate") {
  group = "application"
  description =
      "Applies pending database migrations (reads POSTGRES_URL, POSTGRES_USER, POSTGRES_PASSWORD)"
  classpath = sourceSets["main"].runtimeClasspath
  mainClass = "dev.lawlan.runline.engine.db.MigrateKt"
}

// What every run's class loader gets: the Runner, core and the Kotlin library, resolved through the
// Runner so versions match, and nothing of the Engine.
val runRuntime by configurations.creating {
  isCanBeConsumed = false
  isCanBeResolved = true
}

dependencies { runRuntime(project(":runner")) }

// The Engine as deployed: the fat jar, with the jars every run's class loader gets in a directory
// of their own (ADR-001). RUNLINE_RUNTIME_DIR points at that directory; the Engine refuses to
// start if it holds Engine classes.
val engineDistribution by
    tasks.registering(Sync::class) {
      group = "distribution"
      description = "Assembles the deployable Engine: engine.jar and run-runtime/*.jar"
      from(tasks.named("shadowJar")) { rename { "engine.jar" } }
      from(runRuntime) { into("run-runtime") }
      into(layout.buildDirectory.dir("engine-dist"))
    }

// Runs the tests that start the packaged Engine as its own process (Docker is needed for the
// database). Part of check.
val packagedTest by
    tasks.registering(Test::class) {
      group = "verification"
      description = "Tests the packaged Engine process: isolation of runs, startup checks, shutdown"
      testClassesDirs = sourceSets.test.get().output.classesDirs
      classpath = sourceSets.test.get().runtimeClasspath
      useJUnitPlatform()
      filter { includeTestsMatching("*.packaged.*") }
      dependsOn(engineDistribution)
      val dist = layout.buildDirectory.dir("engine-dist")
      val launcher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
      doFirst {
        systemProperty("runline.dist", dist.get().asFile.absolutePath)
        systemProperty("runline.java", launcher.get().executablePath.asFile.absolutePath)
      }
    }

tasks.check { dependsOn(packagedTest) }
