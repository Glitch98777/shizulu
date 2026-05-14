package com.shizulu.manager

enum class TrustLevel {
    UNSIGNED,
    INTEGRITY_CHECKED,
    SIGNED_UNVERIFIED,
    VERIFIED_AUTHOR,
    OFFICIAL,
    TAMPERED
}

data class TrustReport(
    val level: TrustLevel,
    val label: String,
    val message: String,
    val warning: Boolean
)

object ShizuleTrust {
    fun evaluate(shizule: Shizule): TrustReport {
        val tier = shizule.tier.lowercase()
        if ("official" in tier) {
            return TrustReport(
                level = TrustLevel.OFFICIAL,
                label = "Official Shizulu module",
                message = "This module is marked official by the Shizulu Store metadata.",
                warning = false
            )
        }
        if (shizule.author.verified || "verified" in tier) {
            return TrustReport(
                level = TrustLevel.VERIFIED_AUTHOR,
                label = "Verified author",
                message = "The author is marked verified by Store metadata. Review commands before running.",
                warning = false
            )
        }
        val signature = shizule.signature
        if (signature == null) {
            return TrustReport(
                level = TrustLevel.UNSIGNED,
                label = "Unsigned",
                message = "This module has no signature or integrity digest. Install only if you trust the source.",
                warning = true
            )
        }
        if (signature.sha256.isBlank()) {
            return TrustReport(
                level = TrustLevel.SIGNED_UNVERIFIED,
                label = "Signed metadata only",
                message = "This module names an author but does not include a verifiable digest.",
                warning = true
            )
        }
        return if (signature.sha256.equals(shizule.rawSha256, ignoreCase = true)) {
            TrustReport(
                level = TrustLevel.INTEGRITY_CHECKED,
                label = "Integrity checked",
                message = "SHA-256 matches the module content. This checks tampering, not real identity.",
                warning = false
            )
        } else {
            TrustReport(
                level = TrustLevel.TAMPERED,
                label = "Digest mismatch",
                message = "SHA-256 does not match this module. It may have been changed after publishing.",
                warning = true
            )
        }
    }
}
