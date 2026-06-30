package org.monogram.data.mapper.message

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.monogram.domain.models.FactCheckModel
import org.monogram.domain.models.InlineKeyboardButtonModel
import org.monogram.domain.models.InlineKeyboardButtonType
import org.monogram.domain.models.KeyboardButtonModel
import org.monogram.domain.models.KeyboardButtonType
import org.monogram.domain.models.MessageEntity
import org.monogram.domain.models.MessageReactionModel
import org.monogram.domain.models.ReactionSender
import org.monogram.domain.models.ReplyMarkupModel
import org.monogram.domain.models.SuggestedPostInfoModel
import org.monogram.domain.models.SuggestedPostPriceModel
import org.monogram.domain.models.SuggestedPostStateModel

private val messageUiPayloadJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

internal fun ReplyMarkupPayload.encode(): String =
    messageUiPayloadJson.encodeToString(ReplyMarkupPayload.serializer(), this)

internal fun decodeReplyMarkupPayload(raw: String?): ReplyMarkupPayload? {
    if (raw.isNullOrBlank()) return null
    return runCatching {
        messageUiPayloadJson.decodeFromString(ReplyMarkupPayload.serializer(), raw)
    }.getOrNull()
}

internal fun ReactionsPayload.encode(): String =
    messageUiPayloadJson.encodeToString(ReactionsPayload.serializer(), this)

internal fun decodeReactionsPayload(raw: String?): ReactionsPayload? {
    if (raw.isNullOrBlank()) return null
    return runCatching {
        messageUiPayloadJson.decodeFromString(ReactionsPayload.serializer(), raw)
    }.getOrNull()
}

internal fun FactCheckPayload.encode(): String =
    messageUiPayloadJson.encodeToString(FactCheckPayload.serializer(), this)

internal fun decodeFactCheckPayload(raw: String?): FactCheckPayload? {
    if (raw.isNullOrBlank()) return null
    return runCatching {
        messageUiPayloadJson.decodeFromString(FactCheckPayload.serializer(), raw)
    }.getOrNull()
}

internal fun SuggestedPostInfoPayload.encode(): String =
    messageUiPayloadJson.encodeToString(SuggestedPostInfoPayload.serializer(), this)

internal fun decodeSuggestedPostInfoPayload(raw: String?): SuggestedPostInfoPayload? {
    if (raw.isNullOrBlank()) return null
    return runCatching {
        messageUiPayloadJson.decodeFromString(SuggestedPostInfoPayload.serializer(), raw)
    }.getOrNull()
}

@Serializable
internal sealed interface ReplyMarkupPayload {
    @Serializable
    data class InlineKeyboard(
        val rows: List<List<InlineKeyboardButtonPayload>> = emptyList()
    ) : ReplyMarkupPayload

    @Serializable
    data class ShowKeyboard(
        val rows: List<List<KeyboardButtonPayload>> = emptyList(),
        val isPersistent: Boolean = false,
        val resizeKeyboard: Boolean = false,
        val oneTime: Boolean = false,
        val isPersonal: Boolean = false,
        val inputFieldPlaceholder: String = ""
    ) : ReplyMarkupPayload

    @Serializable
    data class RemoveKeyboard(val isPersonal: Boolean = false) : ReplyMarkupPayload

    @Serializable
    data class ForceReply(
        val isPersonal: Boolean = false,
        val inputFieldPlaceholder: String = ""
    ) : ReplyMarkupPayload
}

@Serializable
internal data class InlineKeyboardButtonPayload(
    val text: String,
    val type: InlineKeyboardButtonTypePayload
)

@Serializable
internal sealed interface InlineKeyboardButtonTypePayload {
    @Serializable
    data class Url(val url: String) : InlineKeyboardButtonTypePayload

    @Serializable
    data class Callback(val data: ByteArray) : InlineKeyboardButtonTypePayload

    @Serializable
    data class WebApp(val url: String) : InlineKeyboardButtonTypePayload

    @Serializable
    data class LoginUrl(val url: String, val id: Long) : InlineKeyboardButtonTypePayload

    @Serializable
    data class SwitchInline(val query: String) : InlineKeyboardButtonTypePayload

    @Serializable
    data class Buy(val slug: String? = null) : InlineKeyboardButtonTypePayload

    @Serializable
    data class User(val userId: Long) : InlineKeyboardButtonTypePayload

    @Serializable
    data object Unsupported : InlineKeyboardButtonTypePayload
}

@Serializable
internal data class KeyboardButtonPayload(
    val text: String,
    val type: KeyboardButtonTypePayload
)

@Serializable
internal sealed interface KeyboardButtonTypePayload {
    @Serializable
    data object Text : KeyboardButtonTypePayload

    @Serializable
    data object RequestPhoneNumber : KeyboardButtonTypePayload

    @Serializable
    data object RequestLocation : KeyboardButtonTypePayload

    @Serializable
    data class RequestPoll(
        val forceQuiz: Boolean = false,
        val forceRegular: Boolean = false
    ) : KeyboardButtonTypePayload

    @Serializable
    data class WebApp(val url: String) : KeyboardButtonTypePayload

    @Serializable
    data class RequestUsers(val id: Int) : KeyboardButtonTypePayload

    @Serializable
    data class RequestChat(val id: Int) : KeyboardButtonTypePayload

    @Serializable
    data object Unsupported : KeyboardButtonTypePayload
}

@Serializable
internal data class ReactionsPayload(
    val reactions: List<MessageReactionPayload> = emptyList()
)

@Serializable
internal data class MessageReactionPayload(
    val emoji: String? = null,
    val customEmojiId: Long? = null,
    val customEmojiPath: String? = null,
    val count: Int,
    val isChosen: Boolean,
    val recentSenders: List<ReactionSenderPayload> = emptyList()
)

@Serializable
internal data class ReactionSenderPayload(
    val id: Long,
    val name: String = "",
    val avatar: String? = null
)

@Serializable
internal data class FactCheckPayload(
    val text: String,
    val entities: List<MessageEntity> = emptyList(),
    val countryCode: String? = null
)

@Serializable
internal data class SuggestedPostInfoPayload(
    val price: SuggestedPostPricePayload? = null,
    val sendDate: Int = 0,
    val state: SuggestedPostStatePayload = SuggestedPostStatePayload.PENDING,
    val canBeApproved: Boolean = false,
    val canBeDeclined: Boolean = false
)

@Serializable
internal sealed interface SuggestedPostPricePayload {
    @Serializable
    data class Star(val starCount: Long) : SuggestedPostPricePayload

    @Serializable
    data class Ton(val toncoinCentCount: Long) : SuggestedPostPricePayload
}

@Serializable
internal enum class SuggestedPostStatePayload {
    PENDING,
    APPROVED,
    DECLINED
}

internal fun ReplyMarkupModel.toPayload(): ReplyMarkupPayload = when (this) {
    is ReplyMarkupModel.InlineKeyboard -> ReplyMarkupPayload.InlineKeyboard(
        rows = rows.map { row ->
            row.map { button ->
                InlineKeyboardButtonPayload(
                    text = button.text,
                    type = button.type.toPayload()
                )
            }
        }
    )

    is ReplyMarkupModel.ShowKeyboard -> ReplyMarkupPayload.ShowKeyboard(
        rows = rows.map { row ->
            row.map { button ->
                KeyboardButtonPayload(
                    text = button.text,
                    type = button.type.toPayload()
                )
            }
        },
        isPersistent = isPersistent,
        resizeKeyboard = resizeKeyboard,
        oneTime = oneTime,
        isPersonal = isPersonal,
        inputFieldPlaceholder = inputFieldPlaceholder
    )

    is ReplyMarkupModel.RemoveKeyboard -> ReplyMarkupPayload.RemoveKeyboard(isPersonal)
    is ReplyMarkupModel.ForceReply -> ReplyMarkupPayload.ForceReply(
        isPersonal,
        inputFieldPlaceholder
    )
}

internal fun ReplyMarkupPayload.toDomain(): ReplyMarkupModel = when (this) {
    is ReplyMarkupPayload.InlineKeyboard -> ReplyMarkupModel.InlineKeyboard(
        rows = rows.map { row ->
            row.map { button ->
                InlineKeyboardButtonModel(
                    text = button.text,
                    type = button.type.toDomain()
                )
            }
        }
    )

    is ReplyMarkupPayload.ShowKeyboard -> ReplyMarkupModel.ShowKeyboard(
        rows = rows.map { row ->
            row.map { button ->
                KeyboardButtonModel(
                    text = button.text,
                    type = button.type.toDomain()
                )
            }
        },
        isPersistent = isPersistent,
        resizeKeyboard = resizeKeyboard,
        oneTime = oneTime,
        isPersonal = isPersonal,
        inputFieldPlaceholder = inputFieldPlaceholder
    )

    is ReplyMarkupPayload.RemoveKeyboard -> ReplyMarkupModel.RemoveKeyboard(isPersonal)
    is ReplyMarkupPayload.ForceReply -> ReplyMarkupModel.ForceReply(
        isPersonal,
        inputFieldPlaceholder
    )
}

internal fun InlineKeyboardButtonType.toPayload(): InlineKeyboardButtonTypePayload = when (this) {
    is InlineKeyboardButtonType.Url -> InlineKeyboardButtonTypePayload.Url(url)
    is InlineKeyboardButtonType.Callback -> InlineKeyboardButtonTypePayload.Callback(data)
    is InlineKeyboardButtonType.WebApp -> InlineKeyboardButtonTypePayload.WebApp(url)
    is InlineKeyboardButtonType.LoginUrl -> InlineKeyboardButtonTypePayload.LoginUrl(url, id)
    is InlineKeyboardButtonType.SwitchInline -> InlineKeyboardButtonTypePayload.SwitchInline(query)
    is InlineKeyboardButtonType.Buy -> InlineKeyboardButtonTypePayload.Buy(slug)
    is InlineKeyboardButtonType.User -> InlineKeyboardButtonTypePayload.User(userId)
    InlineKeyboardButtonType.Unsupported -> InlineKeyboardButtonTypePayload.Unsupported
}

internal fun InlineKeyboardButtonTypePayload.toDomain(): InlineKeyboardButtonType = when (this) {
    is InlineKeyboardButtonTypePayload.Url -> InlineKeyboardButtonType.Url(url)
    is InlineKeyboardButtonTypePayload.Callback -> InlineKeyboardButtonType.Callback(data)
    is InlineKeyboardButtonTypePayload.WebApp -> InlineKeyboardButtonType.WebApp(url)
    is InlineKeyboardButtonTypePayload.LoginUrl -> InlineKeyboardButtonType.LoginUrl(url, id)
    is InlineKeyboardButtonTypePayload.SwitchInline -> InlineKeyboardButtonType.SwitchInline(query)
    is InlineKeyboardButtonTypePayload.Buy -> InlineKeyboardButtonType.Buy(slug)
    is InlineKeyboardButtonTypePayload.User -> InlineKeyboardButtonType.User(userId)
    InlineKeyboardButtonTypePayload.Unsupported -> InlineKeyboardButtonType.Unsupported
}

internal fun KeyboardButtonType.toPayload(): KeyboardButtonTypePayload = when (this) {
    KeyboardButtonType.Text -> KeyboardButtonTypePayload.Text
    KeyboardButtonType.RequestPhoneNumber -> KeyboardButtonTypePayload.RequestPhoneNumber
    KeyboardButtonType.RequestLocation -> KeyboardButtonTypePayload.RequestLocation
    is KeyboardButtonType.RequestPoll -> KeyboardButtonTypePayload.RequestPoll(
        forceQuiz,
        forceRegular
    )

    is KeyboardButtonType.WebApp -> KeyboardButtonTypePayload.WebApp(url)
    is KeyboardButtonType.RequestUsers -> KeyboardButtonTypePayload.RequestUsers(id)
    is KeyboardButtonType.RequestChat -> KeyboardButtonTypePayload.RequestChat(id)
    KeyboardButtonType.Unsupported -> KeyboardButtonTypePayload.Unsupported
}

internal fun KeyboardButtonTypePayload.toDomain(): KeyboardButtonType = when (this) {
    KeyboardButtonTypePayload.Text -> KeyboardButtonType.Text
    KeyboardButtonTypePayload.RequestPhoneNumber -> KeyboardButtonType.RequestPhoneNumber
    KeyboardButtonTypePayload.RequestLocation -> KeyboardButtonType.RequestLocation
    is KeyboardButtonTypePayload.RequestPoll -> KeyboardButtonType.RequestPoll(
        forceQuiz,
        forceRegular
    )

    is KeyboardButtonTypePayload.WebApp -> KeyboardButtonType.WebApp(url)
    is KeyboardButtonTypePayload.RequestUsers -> KeyboardButtonType.RequestUsers(id)
    is KeyboardButtonTypePayload.RequestChat -> KeyboardButtonType.RequestChat(id)
    KeyboardButtonTypePayload.Unsupported -> KeyboardButtonType.Unsupported
}

internal fun List<MessageReactionModel>.toPayload(): ReactionsPayload = ReactionsPayload(
    reactions = map { reaction ->
        MessageReactionPayload(
            emoji = reaction.emoji,
            customEmojiId = reaction.customEmojiId,
            customEmojiPath = reaction.customEmojiPath,
            count = reaction.count,
            isChosen = reaction.isChosen,
            recentSenders = reaction.recentSenders.map { sender ->
                ReactionSenderPayload(
                    id = sender.id,
                    name = sender.name,
                    avatar = sender.avatar
                )
            }
        )
    }
)

internal fun ReactionsPayload.toDomain(): List<MessageReactionModel> = reactions.map { reaction ->
    MessageReactionModel(
        emoji = reaction.emoji,
        customEmojiId = reaction.customEmojiId,
        customEmojiPath = reaction.customEmojiPath,
        count = reaction.count,
        isChosen = reaction.isChosen,
        recentSenders = reaction.recentSenders.map { sender ->
            ReactionSender(
                id = sender.id,
                name = sender.name,
                avatar = sender.avatar
            )
        }
    )
}

internal fun FactCheckModel.toPayload(): FactCheckPayload = FactCheckPayload(
    text = text,
    entities = entities,
    countryCode = countryCode
)

internal fun FactCheckPayload.toDomain(): FactCheckModel = FactCheckModel(
    text = text,
    entities = entities,
    countryCode = countryCode
)

internal fun SuggestedPostInfoModel.toPayload(): SuggestedPostInfoPayload =
    SuggestedPostInfoPayload(
        price = price?.toPayload(),
        sendDate = sendDate,
        state = state.toPayload(),
        canBeApproved = canBeApproved,
        canBeDeclined = canBeDeclined
    )

internal fun SuggestedPostInfoPayload.toDomain(): SuggestedPostInfoModel = SuggestedPostInfoModel(
    price = price?.toDomain(),
    sendDate = sendDate,
    state = state.toDomain(),
    canBeApproved = canBeApproved,
    canBeDeclined = canBeDeclined
)

internal fun SuggestedPostPriceModel.toPayload(): SuggestedPostPricePayload = when (this) {
    is SuggestedPostPriceModel.Star -> SuggestedPostPricePayload.Star(starCount)
    is SuggestedPostPriceModel.Ton -> SuggestedPostPricePayload.Ton(toncoinCentCount)
}

internal fun SuggestedPostPricePayload.toDomain(): SuggestedPostPriceModel = when (this) {
    is SuggestedPostPricePayload.Star -> SuggestedPostPriceModel.Star(starCount)
    is SuggestedPostPricePayload.Ton -> SuggestedPostPriceModel.Ton(toncoinCentCount)
}

internal fun SuggestedPostStateModel.toPayload(): SuggestedPostStatePayload = when (this) {
    SuggestedPostStateModel.PENDING -> SuggestedPostStatePayload.PENDING
    SuggestedPostStateModel.APPROVED -> SuggestedPostStatePayload.APPROVED
    SuggestedPostStateModel.DECLINED -> SuggestedPostStatePayload.DECLINED
}

internal fun SuggestedPostStatePayload.toDomain(): SuggestedPostStateModel = when (this) {
    SuggestedPostStatePayload.PENDING -> SuggestedPostStateModel.PENDING
    SuggestedPostStatePayload.APPROVED -> SuggestedPostStateModel.APPROVED
    SuggestedPostStatePayload.DECLINED -> SuggestedPostStateModel.DECLINED
}
