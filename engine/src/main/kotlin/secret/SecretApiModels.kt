package dev.lawlan.runline.engine.secret

import kotlinx.serialization.Serializable

/** One alias: its [type] (`secret`, `trusted_certificate`, `private_key`), [status], users. */
@Serializable
data class SecretDoc(
    val alias: String,
    val type: String,
    val status: String,
    val usedBy: List<String>,
)

@Serializable data class SecretListResponse(val secrets: List<SecretDoc>)

@Serializable data class ChangedSecretDoc(val alias: String, val usedBy: List<String>)

@Serializable data class ReloadResponse(val aliases: Int, val changed: List<ChangedSecretDoc>)

/** A keystore that cannot be read; [problem] is the category and nothing more. */
@Serializable
data class SecretStoreUnreadableResponse(
    val error: String,
    val message: String,
    val problem: String,
)

fun ListedSecret.toDoc() = SecretDoc(alias, kind.wire, status.wire, usedBy)

fun SecretReloadOutcome.Reloaded.toDoc() =
    ReloadResponse(aliases, changed.map { ChangedSecretDoc(it.alias, it.usedBy) })
