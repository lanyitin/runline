plugins {
  alias(libs.plugins.kotlin.jvm)
  `java-test-fixtures`
}

kotlin { jvmToolchain(25) }

dependencies {
  implementation(project(":core"))

  // Real jars compiled from source for the tests of the Runner and of what is built on it.
  testFixturesImplementation(project(":core"))

  testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }
