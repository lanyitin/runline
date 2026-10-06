plugins { alias(libs.plugins.kotlin.jvm) }

kotlin { jvmToolchain(25) }

// 只依賴 JDK 與 Kotlin 標準函式庫。core 僅作為測試用，供編譯 pipeline 測試輸入。
dependencies {
  testImplementation(project(":core"))
  testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }
