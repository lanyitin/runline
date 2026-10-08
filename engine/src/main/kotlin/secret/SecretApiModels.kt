package dev.lawlan.runline.engine.secret

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * One alias: its [type] (`secret`, `trusted_certificate`, `private_key`), [status], users, and for
 * a certificate entry its certificates (WI-52); a secret has no [certificates] member at all.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class SecretDoc(
    val alias: String,
    val type: String,
    val status: String,
    val usedBy: List<String>,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val certificates: List<CertificateDoc>? = null,
)

/** What may be shown of a certificate: never anything of a key. */
@Serializable
data class CertificateDoc(
    val subject: String,
    val notAfter: String,
    val daysLeft: Long,
    val fingerprint: String,
    /** `valid`, `expiring` (fewer days left than the warning threshold) or `expired`. */
    val expiry: String,
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

fun ListedSecret.toDoc() =
    SecretDoc(
        alias,
        kind.wire,
        status.wire,
        usedBy,
        if (kind == EntryKind.SECRET) null
        else
            certificates.map {
              CertificateDoc(
                  it.info.subject,
                  it.info.notAfter.toString(),
                  it.daysLeft,
                  it.info.fingerprint,
                  it.expiry.wire,
              )
            },
    )

fun SecretReloadOutcome.Reloaded.toDoc() =
    ReloadResponse(aliases, changed.map { ChangedSecretDoc(it.alias, it.usedBy) })
