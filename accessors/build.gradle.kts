plugins {
  alias(libs.plugins.kotlin.jvm)
  `java-test-fixtures`
}

kotlin { jvmToolchain(25) }

// The host's side of the accessors of typed shared resources (ADR-019): the entities behind them
// and the operations on them. Used by the Engine and the development entry. It is deliberately not
// part of the Runner: the Runner's jar is what every run's class loader gets, and nothing of the
// entities (and later the drivers and clients) may be in it.
dependencies {
  implementation(project(":core"))
  implementation(project(":runner"))
  // The JSON of the requests an `openai-compatible` resource merges and the answers it reads.
  implementation(libs.kotlinx.serialization.json)
  // The driver of the first database of `jdbc-pool` (WI-48). It ships with the Engine and is loaded
  // by the Engine's class loader; the run's class loader never has it.
  implementation(libs.postgresql)

  // The behavior every host of accessors must show, run by the Engine's and the development
  // entry's own tests (WI-43): the same acceptance tests for both.
  testFixturesImplementation(kotlin("test-junit5"))
  testFixturesImplementation(libs.kotlinx.serialization.json)
  // A real PostgreSQL for every test of `jdbc-pool` (WI-48): the same image as the Engine's tests.
  testFixturesImplementation(libs.testcontainers.postgresql)
  testFixturesImplementation(libs.postgresql)

  testImplementation(kotlin("test"))
  testImplementation(testFixtures(project(":runner")))
}

// Measurements against a real OpenAI compatible service are run by hand (WI-46) and are not part of
// `check`: they are tagged and left out of `test`, and have a task of their own.
tasks.test { useJUnitPlatform { excludeTags("real-service") } }

tasks.register<Test>("verifyOpenAiService") {
  group = "verification"
  description =
      "Manual: measures a real OpenAI compatible service (RUNLINE_OPENAI_VERIFY_URL, " +
          "RUNLINE_OPENAI_VERIFY_MODEL, ...); the report is build/reports/openai-verification.txt"
  testClassesDirs = sourceSets.test.get().output.classesDirs
  classpath = sourceSets.test.get().runtimeClasspath
  useJUnitPlatform { includeTags("real-service") }
  testLogging { showStandardStreams = true }
  outputs.upToDateWhen { false }
}

// Manual, for the Console's real-browser tests (WI-50): the Fake OpenAI compatible service of the
// tests as a process of its own, held to the protocol by OpenAiServerContract. See
// FakeOpenAiServerMain for its environment.
tasks.register<JavaExec>("fakeOpenAiServer") {
  group = "verification"
  description = "Manual: runs the Fake OpenAI compatible service until stopped (FAKE_OPENAI_*)"
  classpath = sourceSets.testFixtures.get().runtimeClasspath
  mainClass = "dev.lawlan.runline.accessors.fake.FakeOpenAiServerMainKt"
}
