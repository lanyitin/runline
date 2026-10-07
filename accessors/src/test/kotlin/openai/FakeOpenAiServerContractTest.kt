package dev.lawlan.runline.accessors.openai

import dev.lawlan.runline.accessors.fake.ContractTarget
import dev.lawlan.runline.accessors.fake.FakeOpenAiServer
import dev.lawlan.runline.accessors.fake.OpenAiServerContract
import kotlin.test.AfterTest

/** The Fake that stands in for an OpenAI compatible service keeps the protocol's contract. */
class FakeOpenAiServerContractTest : OpenAiServerContract() {
  private val server = FakeOpenAiServer(requiredKey = "sk-contract-key")

  override fun target() = ContractTarget(server.baseUrl, "fake-model", "sk-contract-key")

  @AfterTest fun stop() = server.close()
}
