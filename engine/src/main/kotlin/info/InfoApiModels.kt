package dev.lawlan.runline.engine.info

import kotlinx.serialization.Serializable

/** `GET /api/v1/info`: the least that identifies the build, open to everyone (ADR-016). */
@Serializable
data class InfoResponse(val version: String, val commitHash: String, val dirty: Boolean)

/** Who the token of the request belongs to. [role] is `developer` or `admin`. */
@Serializable data class CallerResponse(val name: String, val role: String)

/** `GET /api/v1/system`: the details of the build and the running Engine, and who is asking. */
@Serializable
data class SystemResponse(
    val version: String,
    val commitHash: String,
    val dirty: Boolean,
    /** When the commit was made, not when it was built. */
    val buildTime: String,
    val jdk: String,
    val startedAt: String,
    val uptimeSeconds: Long,
    val allowListVersion: String,
    val caller: CallerResponse,
)
