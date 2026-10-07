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

  // The behavior every host of accessors must show, run by the Engine's and the development
  // entry's own tests (WI-43): the same acceptance tests for both.
  testFixturesImplementation(kotlin("test-junit5"))

  testImplementation(kotlin("test"))
  testImplementation(testFixtures(project(":runner")))
}

tasks.test { useJUnitPlatform() }
