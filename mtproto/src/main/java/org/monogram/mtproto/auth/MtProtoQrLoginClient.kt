package org.monogram.mtproto.auth

import org.monogram.mtproto.tl.generated.cloud.layer223.auth.Authorization_fb75ff221f
import org.monogram.mtproto.tl.generated.cloud.layer223.auth.ExportLoginToken
import org.monogram.mtproto.tl.generated.cloud.layer223.auth.ImportLoginToken
import org.monogram.mtproto.tl.generated.cloud.layer223.auth.LoginTokenMigrateTo
import org.monogram.mtproto.tl.generated.cloud.layer223.auth.LoginTokenSuccess
import org.monogram.mtproto.tl.generated.cloud.layer223.auth.LoginToken_1f26fafac9

sealed interface MtProtoQrLoginState {
    /** QR payload is live; poll again before [expiresAtSeconds]. */
    data class Waiting(val token: ByteArray, val expiresAtSeconds: Int) : MtProtoQrLoginState

    /** The token was issued by another DC; re-import it there before continuing. */
    data class MigrationNeeded(val dcId: Int, val token: ByteArray) : MtProtoQrLoginState

    /** The other device approved this login. */
    data class Authorized(val authorization: Authorization_fb75ff221f) : MtProtoQrLoginState
}

fun interface MtProtoQrLoginExecutor {
    suspend fun execute(method: org.monogram.mtproto.tl.runtime.TlMethod<*>): org.monogram.mtproto.tl.runtime.TlObject
}

/**
 * QR-login token lifecycle (`auth.exportLoginToken`/`auth.importLoginToken`).
 *
 * The caller owns polling and DC transport swaps: [export] classifies every server variant,
 * and [import] continues after a migration on the redirected DC.
 */
class MtProtoQrLoginClient(
    private val executor: MtProtoQrLoginExecutor,
    private val apiId: Int,
    private val apiHash: String,
    private val exceptAuthorizationIds: List<Long> = emptyList(),
) {
    suspend fun export(): MtProtoQrLoginState =
        classify(executor.execute(ExportLoginToken(apiId, apiHash, exceptAuthorizationIds)))

    suspend fun import(token: ByteArray): MtProtoQrLoginState =
        classify(executor.execute(ImportLoginToken(org.monogram.mtproto.tl.runtime.TlBytes.copyOf(token))))

    private fun classify(result: org.monogram.mtproto.tl.runtime.TlObject): MtProtoQrLoginState = when (result) {
        is LoginToken_1f26fafac9 ->
            MtProtoQrLoginState.Waiting(result.token.toByteArray(), result.expires)
        is LoginTokenMigrateTo ->
            MtProtoQrLoginState.MigrationNeeded(result.dcId, result.token.toByteArray())
        is LoginTokenSuccess ->
            MtProtoQrLoginState.Authorized(result.authorization)
        else ->
            throw IllegalStateException("Unsupported auth.exportLoginToken variant ${result.constructorId}")
    }
}
