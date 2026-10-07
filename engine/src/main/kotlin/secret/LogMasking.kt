package dev.lawlan.runline.engine.secret

import ch.qos.logback.classic.pattern.ExtendedThrowableProxyConverter
import ch.qos.logback.classic.pattern.MessageConverter
import ch.qos.logback.classic.spi.ILoggingEvent

/**
 * The message of a log event, with the secrets the Engine knows masked. `logback.xml` uses it for
 * `%msg`, so that every line the Engine writes passes through it ([SecretMasking]).
 */
class MaskedMessageConverter : MessageConverter() {
  override fun convert(event: ILoggingEvent): String = SecretMasking.mask(super.convert(event))
}

/** The exception of a log event (its messages and its trace), likewise masked: `%maskedEx`. */
class MaskedThrowableConverter : ExtendedThrowableProxyConverter() {
  override fun convert(event: ILoggingEvent): String = SecretMasking.mask(super.convert(event))
}
