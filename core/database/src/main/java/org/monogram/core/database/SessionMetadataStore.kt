package org.monogram.core.database

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.monogram.core.database.entity.MetaEntity
import org.monogram.core.database.entity.PeerEntity
import org.monogram.core.database.entity.ProfileMemberEntity
import org.monogram.core.database.entity.ProfileTabsEntity
import org.monogram.core.database.entity.UpdateCursorEntity
import org.monogram.core.models.AuthSession
import org.monogram.core.models.Chat
import org.monogram.core.models.Message
import org.monogram.core.models.PeerId
import org.monogram.core.models.Profile
import org.monogram.core.models.ProfileMember
import org.monogram.core.models.ProfileMemberPage
import org.monogram.core.models.ProfileTab
import org.monogram.core.models.ProfileTabCounts
import org.monogram.core.models.isPlaceholderPeerTitle
import org.monogram.core.models.preferredPeerTitle

/**
 * Non-secret session metadata in Room.
 * Auth keys stay in the native session runtime.
 */
open class SessionMetadataStore(
    private val db: MonogramDatabase? = null,
) {
    open suspend fun saveAuthorized(session: AuthSession) {
        val database = db ?: return
        database.metaDao().upsert(MetaEntity(KEY_USER_ID, session.userId.value.toString()))
        database.metaDao().upsert(MetaEntity(KEY_DC_ID, session.dcId.toString()))
        database.metaDao().upsert(MetaEntity(KEY_AUTHORIZED, "1"))
    }

    open suspend fun savePremium(premium: Boolean) {
        db?.metaDao()?.upsert(MetaEntity(KEY_PREMIUM, if (premium) "1" else "0"))
    }

    open suspend fun readPremium(): Boolean =
        db?.metaDao()?.get(KEY_PREMIUM)?.value == "1"

    open suspend fun clearAuthorized() {
        val database = db ?: return
        database.metaDao().upsert(MetaEntity(KEY_AUTHORIZED, "0"))
        database.metaDao().upsert(MetaEntity(KEY_USER_ID, ""))
        database.metaDao().upsert(MetaEntity(KEY_DC_ID, ""))
        database.metaDao().upsert(MetaEntity(KEY_PREMIUM, "0"))
    }

    open suspend fun clearSession() {
        val database = db ?: return
        database.metaDao().clear()
        database.updateCursorDao().clear()
        database.profileTabsDao().clear()
        database.profileMembersDao().clear()
        database.profileMediaDao().clear()
        database.profileCommonDao().clear()
    }

    open suspend fun readAuthorizedUserId(): PeerId? =
        db?.metaDao()?.get(KEY_USER_ID)?.value?.toLongOrNull()?.let(::PeerId)

    open suspend fun isAuthorized(): Boolean =
        db?.metaDao()?.get(KEY_AUTHORIZED)?.value == "1"

    /** Blocking startup read; call from IO, not the UI hot path. */
    fun isAuthorizedBlocking(): Boolean =
        runBlocking(Dispatchers.IO) { isAuthorized() }

    open suspend fun upsertPeersFromChats(chats: List<Chat>) {
        val database = db ?: return
        if (chats.isEmpty()) return
        withContext(Dispatchers.IO) {
            database.withTransaction {
                val existing = database.peerDao()
                    .getByIds(chats.map { it.id.value }.distinct())
                    .associateBy { it.id }
                val peers = chats.map { chat ->
                    val kind = when {
                        chat.isChannel -> "channel"
                        chat.isGroup -> "group"
                        else -> "user"
                    }
                    val prior = existing[chat.id.value]
                    PeerEntity(
                        id = chat.id.value,
                        kind = kind,
                        title = preferredPeerTitle(chat.title, prior?.title, chat.id.value),
                        username = prior?.username,
                        about = prior?.about,
                        avatarCacheKey = chat.photoCacheKey ?: prior?.avatarCacheKey,
                        status = chat.peerStatus ?: prior?.status,
                        statusAt = chat.peerStatusAt ?: prior?.statusAt,
                        emojiStatusDocumentId = chat.emojiStatusDocumentId ?: prior?.emojiStatusDocumentId,
                        extraJson = prior?.extraJson,
                        isSelf = prior?.isSelf ?: false,
                    )
                }
                database.peerDao().upsertAll(peers)
            }
        }
    }

    open suspend fun upsertProfile(profile: Profile) {
        val database = db ?: return
        val prior = database.peerDao().get(profile.id.value)
        database.peerDao().upsertAll(listOf(profile.toPeerEntity(prior)))
    }

    /** History/user-min rows. Never wipe a cached full profile with empty fields. */
    open suspend fun upsertPeerMins(messages: List<Message>) {
        val database = db ?: return
        val names = LinkedHashMap<Long, String>()
        messages.forEach { message ->
            val id = message.senderId?.value ?: return@forEach
            val name = message.senderName?.takeIf { it.isNotBlank() } ?: return@forEach
            names.putIfAbsent(id, name)
        }
        val emoji = LinkedHashMap<Long, Long>()
        messages.forEach { message ->
            val id = message.senderId?.value ?: return@forEach
            val documentId = message.senderEmojiStatusDocumentId ?: return@forEach
            emoji.putIfAbsent(id, documentId)
        }
        if (names.isEmpty()) return
        withContext(Dispatchers.IO) {
            database.withTransaction {
                val existing = database.peerDao().getByIds(names.keys.toList()).associateBy { it.id }
                val peers = names.mapNotNull { (id, name) ->
                    val prior = existing[id]
                    if (prior?.kind == "channel" || prior?.kind == "group" || prior?.kind == "chat") {
                        return@mapNotNull if (
                            isPlaceholderPeerTitle(prior.title, id) &&
                            !isPlaceholderPeerTitle(name, id)
                        ) {
                            prior.copy(
                                title = name,
                                emojiStatusDocumentId = emoji[id] ?: prior.emojiStatusDocumentId,
                            )
                        } else {
                            null
                        }
                    }
                    val base = prior ?: PeerEntity(id = id, kind = "user", title = name)
                    base.copy(
                        title = preferredPeerTitle(name, base.title, id),
                        emojiStatusDocumentId = emoji[id] ?: base.emojiStatusDocumentId,
                    )
                }
                if (peers.isNotEmpty()) database.peerDao().upsertAll(peers)
            }
        }
    }

    open suspend fun updatePeerStatus(peerId: Long, status: String?, at: Long?) {
        val database = db ?: return
        database.withTransaction {
            database.peerDao().updateStatus(peerId, status, at)
            database.chatDao().updatePeerStatus(peerId, status, at)
        }
    }

    open suspend fun updatePeerEmojiStatus(peerId: Long, documentId: Long?) {
        val database = db ?: return
        database.withTransaction {
            database.peerDao().updateEmojiStatus(peerId, documentId)
            database.chatDao().updateEmojiStatus(peerId, documentId)
        }
    }

    open suspend fun readProfile(peerId: Long): Profile? =
        db?.peerDao()?.get(peerId)?.toProfile()

    /** Cached shared-media counts for the profile tab strip. */
    open suspend fun readProfileTabCounts(peerId: Long): ProfileTabCounts? =
        db?.profileTabsDao()?.get(peerId)?.let { ProfileTabCounts.parse(it.tabsJson) }

    open suspend fun saveProfileTabCounts(peerId: Long, counts: ProfileTabCounts) {
        val database = db ?: return
        val encoded = counts.serialize() ?: return
        database.profileTabsDao().upsert(
            ProfileTabsEntity(
                peerId = peerId,
                tabsJson = encoded,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    /** Cached first page of a peer's member list. */
    open suspend fun readProfileMembers(peerId: Long): ProfileMemberPage? {
        val database = db ?: return null
        return withContext(Dispatchers.IO) {
            val rows = database.profileMembersDao().list(peerId)
            if (rows.isEmpty()) null
            else {
                val total = rows.maxOf { it.totalCount }.takeIf { it > 0 } ?: rows.size
                ProfileMemberPage(
                    count = total,
                    members = rows.map { it.toModel() },
                )
            }
        }
    }

    open suspend fun saveProfileMembers(peerId: Long, page: ProfileMemberPage) {
        val database = db ?: return
        database.withTransaction {
            database.profileMembersDao().delete(peerId)
            if (page.members.isNotEmpty()) {
                database.profileMembersDao().upsertAll(
                    page.members.mapIndexed { index, member ->
                        member.toEntity(peerId, index, page.count)
                    },
                )
            }
        }
    }

    open suspend fun readProfileMedia(peerId: Long): Map<ProfileTab, List<Message>> {
        val database = db ?: return emptyMap()
        return withContext(Dispatchers.IO) {
            database.profileMediaDao().list(peerId)
                .groupBy { ProfileTab.fromWire(it.tab) }
                .mapNotNull { (tab, rows) -> tab?.let { it to rows.map { row -> row.toMessage() } } }
                .toMap()
        }
    }

    open suspend fun saveProfileMedia(peerId: Long, tab: ProfileTab, messages: List<Message>) {
        val database = db ?: return
        database.withTransaction {
            database.profileMediaDao().deleteTab(peerId, tab.wire)
            if (messages.isNotEmpty()) {
                database.profileMediaDao().upsertAll(
                    messages.mapIndexed { index, message ->
                        message.toProfileMediaEntity(peerId, tab.wire, index)
                    },
                )
            }
        }
    }

    open suspend fun readProfileCommonChats(peerId: Long): List<Chat> {
        val database = db ?: return emptyList()
        return withContext(Dispatchers.IO) {
            database.profileCommonDao().list(peerId).map { it.toChat() }
        }
    }

    open suspend fun saveProfileCommonChats(peerId: Long, chats: List<Chat>) {
        val database = db ?: return
        database.withTransaction {
            database.profileCommonDao().delete(peerId)
            if (chats.isNotEmpty()) {
                database.profileCommonDao().upsertAll(
                    chats.mapIndexed { index, chat -> chat.toProfileCommonEntity(peerId, index) },
                )
            }
        }
    }

    open suspend fun saveUpdatesCursor(pts: Int, qts: Int, date: Int, seq: Int) {
        db?.updateCursorDao()?.upsert(
            UpdateCursorEntity(
                key = KEY_PRIMARY_CURSOR,
                pts = pts,
                qts = qts,
                date = date,
                seq = seq,
            ),
        )
    }

    open suspend fun readUpdatesCursor(): UpdateCursorEntity? =
        db?.updateCursorDao()?.get(KEY_PRIMARY_CURSOR)

    open suspend fun readMeta(key: String): String? =
        db?.metaDao()?.get(key)?.value

    open suspend fun writeMeta(key: String, value: String) {
        db?.metaDao()?.upsert(MetaEntity(key, value))
    }

    companion object {
        const val KEY_USER_ID = "session.user_id"
        const val KEY_DC_ID = "session.dc_id"
        const val KEY_AUTHORIZED = "session.authorized"
        const val KEY_PREMIUM = "session.premium"
        const val KEY_PRIMARY_CURSOR = "updates.primary"
    }
}
