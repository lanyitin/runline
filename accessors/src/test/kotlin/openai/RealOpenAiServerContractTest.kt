package dev.lawlan.runline.accessors.openai

import dev.lawlan.runline.accessors.fake.ContractTarget
import dev.lawlan.runline.accessors.fake.OpenAiServerContract
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * The same contract on a real OpenAI compatible service, for the people who have one (WI-46). It
 * runs only where `RUNLINE_OPENAI_CONTRACT_URL` (the base address with its root path, for example
 * `http://localhost:8000/api/v1`), `RUNLINE_OPENAI_CONTRACT_MODEL` and, if the service wants one,
 * `RUNLINE_OPENAI_CONTRACT_KEY` are set; elsewhere it is reported as skipped, which is not a pass.
 */
@EnabledIfEnvironmentVariable(named = "RUNLINE_OPENAI_CONTRACT_URL", matches = ".+")
class RealOpenAiServerContractTest : OpenAiServerContract() {
  override fun target() =
      ContractTarget(
          System.getenv("RUNLINE_OPENAI_CONTRACT_URL"),
          System.getenv("RUNLINE_OPENAI_CONTRACT_MODEL") ?: "default",
          System.getenv("RUNLINE_OPENAI_CONTRACT_KEY")?.takeIf { it.isNotEmpty() },
          missingEndpointsAllowed = true,
      )
}
