package dev.lawlan.runline.accessors.fake

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch

/**
 * Runs [FakeOpenAiServer] as a process of its own, for the Console's real-browser tests (WI-50),
 * which define an `openai-compatible` resource on a running Engine and check it: `./gradlew
 * :accessors:fakeOpenAiServer`. Local only, never part of `check`.
 *
 * Environment: `FAKE_OPENAI_KEY_FILE` (optional) is a file whose text, trimmed, is the key a
 * request must carry (`Authorization: Bearer <key>`), otherwise 401; `FAKE_OPENAI_CHAT_DELAY_MS`
 * (optional) keeps each chat completion waiting that long before it is answered, so that a request
 * stays in flight. It prints its base address (`FAKE_OPENAI_URL=http://127.0.0.1:<port>/v1`) and
 * writes it to `FAKE_OPENAI_URL_FILE` when that is set; it runs until it is stopped.
 */
fun main() {
  val key = System.getenv("FAKE_OPENAI_KEY_FILE")?.let { Files.readString(Path.of(it)).trim() }
  val delay = System.getenv("FAKE_OPENAI_CHAT_DELAY_MS")?.toLong() ?: 0
  val server = FakeOpenAiServer(requiredKey = key)
  server.script = { request, _ ->
    if (request.path.endsWith("/chat/completions")) Thread.sleep(delay)
    false
  }
  System.getenv("FAKE_OPENAI_URL_FILE")?.let { Files.writeString(Path.of(it), server.baseUrl) }
  println("FAKE_OPENAI_URL=${server.baseUrl}")
  System.out.flush()
  CountDownLatch(1).await()
}
