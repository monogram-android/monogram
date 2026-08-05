package org.monogram.data.repository.user

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import org.monogram.data.datasource.cache.UserLocalDataSource
import org.monogram.data.db.dao.KeyValueDao
import org.monogram.data.gateway.UpdateDispatcher
import org.monogram.data.infra.FileObserverHub
import org.monogram.data.infra.LatestByKeyBatcher
import java.util.concurrent.ConcurrentHashMap

internal class UserUpdateSynchronizer(
    private val scope: CoroutineScope,
    private val updates: UpdateDispatcher,
    private val fileObserverHub: FileObserverHub,
    private val userLocal: UserLocalDataSource,
    private val keyValueDao: KeyValueDao,
    private val emojiPathCache: ConcurrentHashMap<Long, String>,
    private val fileIdToUserIdMap: ConcurrentHashMap<Int, Long>,
    private val onUserUpdated: suspend (TdApi.User) -> Unit,
    private val onUserIdChanged: suspend (Long) -> Unit,
    private val onCachedSimCountryIsoChanged: suspend (String?) -> Unit
) {
    private val avatarFileIdToUserIds = ConcurrentHashMap<Int, MutableSet<Long>>()
    private val userIdToAvatarFileIds = ConcurrentHashMap<Long, Set<Int>>()
    private val userStatusBatcher = LatestByKeyBatcher<Long, TdApi.UserStatus>(scope) { statuses ->
        statuses.forEach { (userId, status) ->
            userLocal.updateUserStatus(userId, status)?.let { cached ->
                updateAvatarIndex(cached)
                onUserIdChanged(cached.id)
            }
        }
    }

    fun start() {
        scope.launch {
            userLocal.getAllUsers().forEach { user ->
                updateAvatarIndex(user)
            }
        }

        // updateUser is documented as arriving before the user id is handed to the
        // application, and it is the only introduction of the user object, so it must be
        // lossless. Status goes through the same lane rather than a second subscription:
        // the batcher only conflates per user id, so a lost update here would still lose
        // that user's presence entirely.
        updates.lane(
            name = "users",
            scope = scope,
            filter = { it is TdApi.UpdateUser || it is TdApi.UpdateUserStatus },
        ) { update ->
            when (update) {
                is TdApi.UpdateUser -> {
                    updateAvatarIndex(update.user)
                    onUserUpdated(update.user)
                }

                is TdApi.UpdateUserStatus -> {
                    // Non-suspending, keeps the latest status per user; the batch applies
                    // it to the store off the lane.
                    userStatusBatcher.offer(update.userId, update.status)
                }
            }
        }

        scope.launch {
            fileObserverHub.fileStates.collect { state ->
                if (!state.isDownloaded) return@collect
                val fileId = state.fileId
                val path = state.path ?: return@collect

                avatarFileIdToUserIds[fileId]?.forEach { userId ->
                    onUserIdChanged(userId)
                }

                val userId = fileIdToUserIdMap.remove(fileId)
                if (userId != null) {
                    userLocal.getUser(userId)?.let { user ->
                        val emojiId = user.extractEmojiStatusId()
                        if (emojiId != 0L) {
                            emojiPathCache[emojiId] = path
                        }
                    }
                    onUserIdChanged(userId)
                }
            }
        }

        scope.launch {
            keyValueDao.observeValue(KEY_CACHED_SIM_COUNTRY_ISO).collect { entity ->
                onCachedSimCountryIsoChanged(entity?.value)
            }
        }
    }

    companion object {
        private const val KEY_CACHED_SIM_COUNTRY_ISO = "cached_sim_country_iso"
    }

    private fun updateAvatarIndex(user: TdApi.User) {
        val newFileIds = buildSet {
            user.profilePhoto?.small?.id?.takeIf { it != 0 }?.let(::add)
            user.profilePhoto?.big?.id?.takeIf { it != 0 }?.let(::add)
        }

        val previousFileIds = userIdToAvatarFileIds.put(user.id, newFileIds) ?: emptySet()

        (previousFileIds - newFileIds).forEach { fileId ->
            avatarFileIdToUserIds[fileId]?.let { userIds ->
                userIds.remove(user.id)
                if (userIds.isEmpty()) {
                    avatarFileIdToUserIds.remove(fileId)
                }
            }
        }

        (newFileIds - previousFileIds).forEach { fileId ->
            avatarFileIdToUserIds.getOrPut(fileId) { ConcurrentHashMap.newKeySet() }.add(user.id)
        }
    }
}
