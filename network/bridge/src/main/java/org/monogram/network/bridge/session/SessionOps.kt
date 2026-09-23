package org.monogram.network.bridge.session

import org.monogram.core.common.Outcome
import org.monogram.core.models.AuthState

interface SessionOps {
    suspend fun connect(): Outcome<Unit>
    suspend fun setTestDc(enabled: Boolean): Outcome<Unit> = Outcome.Ok(Unit)
    suspend fun sendAuthCode(phone: String): Outcome<AuthState.AwaitingCode>
    suspend fun resendAuthCode(
        phone: String,
        phoneCodeHash: String,
    ): Outcome<AuthState.AwaitingCode> = sendAuthCode(phone)

    suspend fun signIn(phone: String, phoneCodeHash: String, code: String): Outcome<AuthState>
    suspend fun checkPassword(password: String): Outcome<AuthState.Authorized>
    fun libraryVersion(): String

    /** Applies the "Faster downloads" setting. No handle needed; takes effect next download. */
    fun applyDownloadConcurrency(lanes: Int, parts: Int) {}

    /** Effective `[lanes, parts]` after native clamping. */
    fun downloadConcurrency(): List<Int> = listOf(0, 0)

    fun setFilePartKib(kib: Int) {}
    fun setDownloadChunkKib(kib: Int) {}
    fun downloadChunkKib(): Int = 0

    /** Hint that a dialog is open. Media downloads yield; chats/updates keep running. */
    fun setDialogForeground(active: Boolean) {}

    /** Revokes the server authorization when possible, then tears down local state. */
    suspend fun logout(): Outcome<Unit>

    /** Drops the live TCP/session handle; next RPC restores from the session file. */
    fun hibernate() {}
    fun close()
}
