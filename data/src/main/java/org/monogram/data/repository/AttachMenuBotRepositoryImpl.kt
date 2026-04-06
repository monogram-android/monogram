package org.monogram.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.monogram.core.DispatcherProvider
import org.monogram.core.ScopeProvider
import org.monogram.data.datasource.cache.SettingsCacheDataSource
import org.monogram.data.datasource.remote.SettingsRemoteDataSource
import org.monogram.data.db.dao.AttachBotDao
import org.monogram.data.db.model.AttachBotEntity
import org.monogram.data.gateway.UpdateDispatcher
import org.monogram.data.mapper.toDomain
import org.monogram.domain.models.AttachMenuBotModel
import org.monogram.domain.repository.AttachMenuBotRepository
import org.monogram.domain.repository.CacheProvider

class AttachMenuBotRepositoryImpl(
    private val remote: SettingsRemoteDataSource,
    private val cache: SettingsCacheDataSource,
    private val cacheProvider: CacheProvider,
    private val updates: UpdateDispatcher,
    private val dispatchers: DispatcherProvider,
    private val attachBotDao: AttachBotDao,
    scopeProvider: ScopeProvider
) : AttachMenuBotRepository {

    private val scope = scopeProvider.appScope
    private val attachMenuBots = MutableStateFlow<List<AttachMenuBotModel>>(cacheProvider.attachBots.value)

    init {
        scope.launch {
            updates.attachmentMenuBots.collect { update ->
                cache.putAttachMenuBots(update.bots)
                val bots = update.bots.map { it.toDomain() }
                attachMenuBots.value = bots
                cacheProvider.setAttachBots(bots)

                saveAttachBotsToDb(bots)

                update.bots.forEach { bot ->
                    bot.androidSideMenuIcon?.let { icon ->
                        if (icon.local.path.isEmpty()) {
                            remote.downloadFile(icon.id, 1)
                        }
                    }
                }
            }
        }

        scope.launch {
            updates.file.collect { update ->
                val currentBots = attachMenuBots.value
                if (currentBots.any { it.icon?.icon?.id == update.file.id }) {
                    cache.getAttachMenuBots()?.let { bots ->
                        val domainBots = bots.map { it.toDomain() }
                        attachMenuBots.value = domainBots
                        cacheProvider.setAttachBots(domainBots)
                        saveAttachBotsToDb(domainBots)
                    }
                }
            }
        }

        scope.launch {
            attachBotDao.getAttachBots().collect { entities ->
                val bots = entities.mapNotNull {
                    try {
                        Json.decodeFromString<AttachMenuBotModel>(it.data)
                    } catch (_: Exception) {
                        null
                    }
                }
                if (bots.isNotEmpty()) {
                    attachMenuBots.value = bots
                    cacheProvider.setAttachBots(bots)
                }
            }
        }
    }

    override fun getAttachMenuBots(): Flow<List<AttachMenuBotModel>> {
        return attachMenuBots
    }

    private suspend fun saveAttachBotsToDb(bots: List<AttachMenuBotModel>) {
        withContext(dispatchers.io) {
            attachBotDao.clearAll()
            attachBotDao.insertAttachBots(
                bots.map {
                    AttachBotEntity(it.botUserId, Json.encodeToString(it))
                }
            )
        }
    }
}