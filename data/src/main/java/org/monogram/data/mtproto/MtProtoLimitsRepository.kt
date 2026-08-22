package org.monogram.data.mtproto

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.monogram.domain.models.TelegramLimits
import org.monogram.domain.repository.TelegramLimitsRepository
import org.monogram.mtproto.tl.generated.cloud.layer223.JsonArray
import org.monogram.mtproto.tl.generated.cloud.layer223.JsonBool
import org.monogram.mtproto.tl.generated.cloud.layer223.JsonNull
import org.monogram.mtproto.tl.generated.cloud.layer223.JsonNumber
import org.monogram.mtproto.tl.generated.cloud.layer223.JsonObject
import org.monogram.mtproto.tl.generated.cloud.layer223.JsonObjectValue_c7a772e90b
import org.monogram.mtproto.tl.generated.cloud.layer223.JsonString
import org.monogram.mtproto.tl.generated.cloud.layer223.JsonValue
import org.monogram.mtproto.tl.generated.cloud.layer223.help.AppConfigNotModified
import org.monogram.mtproto.tl.generated.cloud.layer223.help.AppConfig_3a7ef1187f
import org.monogram.mtproto.tl.generated.cloud.layer223.help.GetAppConfig

/** Extracts one integer app-config value; returns null for absent or non-numeric entries. */
internal fun interface MtProtoLimitsIntReader {
    fun read(key: String): Int?
}

/**
 * Server-backed account limits from `help.getAppConfig`.
 *
 * Unknown or absent keys keep their previous values, so partial configs never erase defaults.
 * The server hash is reused so unchanged configs return `appConfigNotModified` cheaply.
 */
internal class MtProtoLimitsRepository(
    private val configSource: TelegramMtProtoBootstrapConfigSource,
    private val transportFactory: MtProtoSessionTransportFactory,
    private val accountSlot: String = DEFAULT_ACCOUNT_SLOT,
) : TelegramLimitsRepository {
    private val mtProtoLimits = MutableStateFlow(TelegramLimits.DEFAULTS)
    override val limits: StateFlow<TelegramLimits> get() = mtProtoLimits

    @Volatile
    private var lastConfigHash: Int = 0

    override suspend fun refresh() {
        val config = configSource.createForAccount(accountSlot)
        transportFactory.open(accountSlot).use { transport ->
            when (val result = transport.execute(GetAppConfig(hash = lastConfigHash))) {
                is AppConfigNotModified -> return
                is AppConfig_3a7ef1187f -> {
                    lastConfigHash = result.hash
                    apply(result.config)
                }
                else -> throw IllegalStateException(
                    "Unsupported help.getAppConfig constructor ${result.constructorId}",
                )
            }
        }
    }

    internal fun apply(config: JsonValue) {
        val entries = config.objectEntries()
        mtProtoLimits.value = TelegramLimits.DEFAULTS.copy(
            forwardedMessageCountMax = entries.int("forwarded_message_count_max"),
            messageReplyQuoteLengthMax = entries.int("quote_length_max"),
            richMessageTextLengthMax = entries.int("rich_message_length_limit"),
            richMessageBlockCountMax = entries.int("rich_message_max_blocks"),
            richMessageDepthMax = entries.int("rich_message_max_depth"),
            richMessageMediaCountMax = entries.int("rich_message_max_media"),
            richMessageTableColumnCountMax = entries.int("rich_message_max_table_cols"),
            checklistTaskCountMax = entries.int("todo_items_max"),
            checklistTaskTextLengthMax = entries.int("todo_item_length_max"),
            checklistTitleLengthMax = entries.int("todo_title_length_max"),
            pollAnswerCountMax = entries.int("poll_answers_max"),
            pollOpenPeriodMax = entries.int("poll_close_period_max"),
            businessStartPageTitleLengthMax = entries.int("intro_title_length_limit"),
            businessStartPageMessageLengthMax = entries.int("intro_description_length_limit"),
        )
    }

    private companion object {
        const val DEFAULT_ACCOUNT_SLOT = "default"
    }
}

private fun JsonValue.objectEntries(): Map<String, JsonValue> =
    (this as? JsonObject)?.value_
        ?.mapNotNull { entry ->
            (entry as? JsonObjectValue_c7a772e90b)?.let { it.key to it.value_ }
        }?.toMap()
        ?: emptyMap()

private fun Map<String, JsonValue>.int(key: String): Int? =
    (this[key] as? JsonNumber)?.value_?.toInt()
