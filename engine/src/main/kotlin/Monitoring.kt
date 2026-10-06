package dev.lawlan.runline.engine

import com.codahale.metrics.*
import io.ktor.server.application.*
import io.ktor.server.metrics.dropwizard.*
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory

fun Application.configureMonitoring() {
  install(DropwizardMetrics) {
    Slf4jReporter.forRegistry(registry)
        .outputTo(LoggerFactory.getLogger("metrics"))
        .convertRatesTo(TimeUnit.SECONDS)
        .convertDurationsTo(TimeUnit.MILLISECONDS)
        .build()
        .start(10, TimeUnit.SECONDS)
  }
}
