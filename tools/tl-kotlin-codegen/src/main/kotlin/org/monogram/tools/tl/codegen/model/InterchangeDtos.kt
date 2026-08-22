package org.monogram.tools.tl.codegen.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonObject

@Serializable
internal data class InterchangeDocumentDto(
    @SerialName("json_schema") val jsonSchema: JsonObject,
    val schema: InterchangeSchemaDto,
)

@Serializable
internal data class InterchangeSchemaDto(
    @SerialName("format_version") val formatVersion: Long,
    val layer: Long?,
    val source: SourceDto,
    val constructors: List<CombinatorDto>,
    val functions: List<CombinatorDto>,
    val finalizations: List<FinalizationDto>,
    @SerialName("partial_applications") val partialApplications: List<ExpressionDto>,
)

@Serializable
internal data class SourceDto(
    val name: String,
    val url: String?,
)

@Serializable
internal data class CombinatorDto(
    val name: String,
    val id: Long,
    @SerialName("id_hex") val idHex: String,
    @SerialName("id_explicit") val idExplicit: Boolean,
    val kind: CombinatorKindDto,
    val arguments: List<ArgumentDto>,
    val result: ExpressionDto,
    val documentation: DocumentationDto,
    val layer: LayerInfoDto,
    val builtin: Boolean,
)

@Serializable
internal enum class CombinatorKindDto {
    @SerialName("constructor") CONSTRUCTOR,
    @SerialName("function") FUNCTION,
}

@Serializable
internal data class LayerInfoDto(
    val schema: Long?,
    val introduced: Long?,
)

@Serializable
internal data class ArgumentDto(
    val name: String?,
    val value: ArgumentValueDto,
    val implicit: Boolean,
    val functional: Boolean,
    val condition: ConditionDto?,
    val description: String?,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("kind")
internal sealed interface ArgumentValueDto {
    @Serializable
    @SerialName("type")
    data class Type(
        val expression: ExpressionDto,
    ) : ArgumentValueDto

    @Serializable
    @SerialName("repetition")
    data class Repetition(
        val multiplicity: ExpressionDto?,
        val arguments: List<ArgumentDto>,
    ) : ArgumentValueDto
}

@Serializable
internal data class ConditionDto(
    val variable: String,
    val bit: Long?,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("kind")
internal sealed interface ExpressionDto {
    @Serializable
    @SerialName("ident")
    data class Ident(
        val name: String,
    ) : ExpressionDto

    @Serializable
    @SerialName("nat")
    data class Nat(
        val value: ULong,
    ) : ExpressionDto

    @Serializable
    @SerialName("hash")
    data object Hash : ExpressionDto

    @Serializable
    @SerialName("add")
    data class Add(
        val left: ExpressionDto,
        val right: ExpressionDto,
    ) : ExpressionDto

    @Serializable
    @SerialName("apply")
    data class Apply(
        val constructor: ExpressionDto,
        val arguments: List<ExpressionDto>,
    ) : ExpressionDto

    @Serializable
    @SerialName("bare")
    data class Bare(
        val inner: ExpressionDto,
    ) : ExpressionDto

    @Serializable
    @SerialName("bang")
    data class Bang(
        val inner: ExpressionDto,
    ) : ExpressionDto
}

@Serializable
internal data class DocumentationDto(
    val description: String?,
    val parameters: Map<String, String>,
    @SerialName("official_url") val officialUrl: String?,
    val links: List<String>,
)

@Serializable
internal data class FinalizationDto(
    val mode: String,
    @SerialName("type") val type: ExpressionDto,
)
