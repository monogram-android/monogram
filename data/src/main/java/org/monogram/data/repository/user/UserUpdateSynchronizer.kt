package org.monogram.data.repository.user

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import org.monogram.data.datasource.cache.UserLocalDataSource
import org.monogram.data.db.dao.KeyValueDao
import org.monogram.data.gateway.UpdateDispatcher
import java.util.concurrent.ConcurrentHashMap

internal class UserUpdateSynchronizer(
    private val scope: CoroutineScope,
    private val updates: UpdateDispatcher,
    private val userLocal: UserLocalDataSource,
    private val keyValueDao: KeyValueDao,
    private val emojiPathCache: ConcurrentHashMap<Long, String>,
    private val fileIdToUserIdMap: ConcurrentHashMap<Int, Long>,
    private val onUserUpdated: suspend (TdApi.User) -> Unit,
    private val onUserIdChanged: suspend (Long) -> Unit,
    private val onCachedSimCountryIsoChanged: suspend (String?) -> Unit
) {
    fun start() {
        scope.launch {
            updates.user.collect { update ->
                onUserUpdated(update.user)
            }
        }

        scope.launch {
            updates.userStatus.collect { update ->
                userLocal.getUser(update.userId)?.let { cached ->
                    cached.status = update.status
                    onUserUpdated(cached)
                }
            }
        }

        scope.launch {
            updates.file.collect { update ->
                val file = update.file
                if (!file.local.isDownloadingCompleted) return@collect

                userLocal.getAllUsers().forEach { user ->
                    val small = user.profilePhoto?.small
                    val big = user.profilePhoto?.big
                    if (small?.id == file.id || big?.id == file.id) {
                        onUserIdChanged(user.id)
                    }
                }

                if (file.local.path.isNotEmpty()) {
                    val userId = fileIdToUserIdMap.remove(file.id)
                    if (userId != null) {
                        userLocal.getUser(userId)?.let { user ->
                            val emojiId = user.extractEmojiStatusId()
                            if (emojiId != 0L) {
                                emojiPathCache[emojiId] = file.local.path
                            }
                        }
                        onUserIdChanged(userId)
                    }
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
}