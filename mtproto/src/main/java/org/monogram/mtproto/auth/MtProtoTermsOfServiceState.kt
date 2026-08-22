package org.monogram.mtproto.auth

/** Result of `help.getTermsOfServiceUpdate`. */
sealed interface MtProtoTermsOfServiceState {
    /** No terms update is pending. */
    data object Current : MtProtoTermsOfServiceState

    /** The user must accept the pending terms before continuing where required. */
    data class Pending(
        val expiresAtSeconds: Int,
        val popup: Boolean,
        val idData: String,
        val text: String,
        val minAgeConfirmYears: Int?,
    ) : MtProtoTermsOfServiceState
}
