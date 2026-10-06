
plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ktfmt) apply false
}

subprojects {
    group = "dev.lawlan.runline"
    version = "1.0.0-SNAPSHOT"

    // ktfmt 預設規則（Meta 風格）；版本由 gradle/libs.versions.toml 單一處固定。
    // 格式化：./gradlew ktfmtFormat; 檢查：./gradlew ktfmtCheck（不納入 check）
    apply(plugin = "com.ncorti.ktfmt.gradle")

    // 測試的最後防線（WI-26）：各處等待自帶逾時並給出診斷訊息；以下兩個上限只為攔截漏網的卡住。
    // 單一測試方法（含生命週期方法）超過上限即中斷並判失敗；中斷救不了的等待（例如 socket 讀取）
    // 則由整個測試 task 的上限終止。兩者都應遠大於最慢的正常測試。
    tasks.withType<Test>().configureEach {
        systemProperty("junit.jupiter.execution.timeout.default", "5m")
        timeout = java.time.Duration.ofMinutes(30)
    }
}
