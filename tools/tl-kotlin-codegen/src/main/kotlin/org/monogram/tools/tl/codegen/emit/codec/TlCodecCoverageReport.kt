package org.monogram.tools.tl.codegen.emit.codec

import org.monogram.tools.tl.codegen.emit.declaration.TlDeclarationGenerationResult
import org.monogram.tools.tl.codegen.emit.registry.TlRegistryGenerationResult
import org.monogram.tools.tl.codegen.emit.registry.TlSchemaRegistryPlan
import org.monogram.tools.tl.codegen.model.TlDeclarationKind
import org.monogram.tools.tl.codegen.model.TlSchemaKey
import org.monogram.tools.tl.codegen.naming.TlSchemaSymbols

const val TL_CODEC_COVERAGE_REPORT_PATH: String = "tl-codec-coverage.json"
const val TL_CODEC_COVERAGE_REPORT_FORMAT_VERSION: Int = 1

/** Stable, machine-readable proof that generated codecs and registries cover each schema snapshot. */
class TlCodecCoverageReport internal constructor(
    val formatVersion: Int,
    val schemas: List<TlCodecCoverageReportSchema>,
) {
    val concreteConstructorCount: Int = schemas.sumOf { it.concreteConstructors.size }
    val methodResultCount: Int = schemas.sumOf { it.methodResults.size }
    val d023ExclusionCount: Int = schemas.sumOf { it.d023Exclusions.size }

    init {
        require(formatVersion == TL_CODEC_COVERAGE_REPORT_FORMAT_VERSION) {
            "Unsupported TL codec coverage report format version: $formatVersion"
        }
        require(schemas == schemas.sortedWith(schemaReportComparator)) {
            "TL codec coverage report schemas must be sorted"
        }
        requireUnique("schema identity", schemas.map { it.schemaKey })
        requireUnique("registry qualified name", schemas.map { it.registryQualifiedName })
        requireUnique("registry relative path", schemas.map { it.registryRelativePath })
    }

    fun toDeterministicJson(): String {
        validate()
        return buildString {
            line("{")
            line("  \"formatVersion\": $formatVersion,")
            line("  \"exclusionDecision\": \"D-023\",")
            line("  \"totals\": {")
            line("    \"schemas\": ${schemas.size},")
            line("    \"concreteConstructors\": $concreteConstructorCount,")
            line("    \"methodResults\": $methodResultCount,")
            line("    \"d023Exclusions\": $d023ExclusionCount")
            line("  },")
            line("  \"schemas\": [")
            schemas.forEachIndexed { index, schema ->
                appendSchema(schema)
                if (index < schemas.lastIndex) append(',')
                append('\n')
            }
            line("  ]")
            line("}")
        }
    }

    private fun validate() {
        require(concreteConstructorCount == schemas.sumOf { it.concreteConstructors.size }) {
            "TL codec coverage concrete constructor count changed"
        }
        require(methodResultCount == schemas.sumOf { it.methodResults.size }) {
            "TL codec coverage method-result count changed"
        }
        require(d023ExclusionCount == schemas.sumOf { it.d023Exclusions.size }) {
            "TL codec coverage D-023 exclusion count changed"
        }
        schemas.forEach(TlCodecCoverageReportSchema::validate)
    }
}

class TlCodecCoverageReportSchema internal constructor(
    val schemaKey: TlSchemaKey,
    val snapshotRelativePath: String?,
    val sourceTlPath: String?,
    val sourceSha256: String?,
    val exportedJsonSha256: String?,
    val tellersCommit: String?,
    val registryQualifiedName: String,
    val registryRelativePath: String,
    val concreteConstructors: List<TlCodecCoverageReportEntry>,
    val methodResults: List<TlCodecCoverageReportEntry>,
    val d023Exclusions: List<TlCodecCoverageExclusionEntry>,
) {
    init {
        validate()
    }

    internal fun validate() {
        require(registryQualifiedName.isNotBlank()) { "Registry qualified name must not be blank for $schemaKey" }
        require(isStableRelativePath(registryRelativePath) && registryRelativePath.endsWith(".kt")) {
            "Registry path must be a stable relative Kotlin path for $schemaKey: $registryRelativePath"
        }
        listOfNotNull(snapshotRelativePath, sourceTlPath).forEach { path ->
            require(isStableRelativePath(path)) { "Schema provenance path must be stable and relative for $schemaKey: $path" }
        }
        listOfNotNull(sourceSha256, exportedJsonSha256).forEach { hash ->
            require(hash.length == 64 && hash.all(::isLowerHexDigit)) {
                "Schema hash must be lowercase SHA-256 for $schemaKey"
            }
        }
        tellersCommit?.let { commit ->
            require(commit.length == 40 && commit.all(::isLowerHexDigit)) {
                "Tellers commit must be a lowercase full Git commit for $schemaKey"
            }
        }
        require(concreteConstructors == concreteConstructors.sortedWith(coverageEntryComparator)) {
            "Concrete constructor coverage entries must be sorted for $schemaKey"
        }
        require(methodResults == methodResults.sortedWith(coverageEntryComparator)) {
            "Method-result coverage entries must be sorted for $schemaKey"
        }
        require(d023Exclusions == d023Exclusions.sortedWith(exclusionEntryComparator)) {
            "D-023 exclusions must be sorted for $schemaKey"
        }
        validateEntries("concrete constructor", schemaKey, concreteConstructors)
        validateEntries("method result", schemaKey, methodResults)
        requireUnique("D-023 exclusion TL name in $schemaKey", d023Exclusions.map { it.tlName })
        requireUnique("D-023 exclusion UInt ID in $schemaKey", d023Exclusions.map { it.constructorId })
        require(d023Exclusions.all { it.declarationKind == TlDeclarationKind.CONSTRUCTOR }) {
            "D-023 exclusions must be constructor pseudo-declarations for $schemaKey"
        }
        val allNames = concreteConstructors.map { it.tlName } + methodResults.map { it.tlName } + d023Exclusions.map { it.tlName }
        val allIds = concreteConstructors.map { it.constructorId } + methodResults.map { it.constructorId } +
            d023Exclusions.map { it.constructorId }
        requireUnique("covered TL name in $schemaKey", allNames)
        requireUnique("covered UInt ID in $schemaKey", allIds)
    }
}

class TlCodecCoverageReportEntry internal constructor(
    val tlName: String,
    val constructorId: UInt,
    val qualifiedCodecName: String,
    val codecRelativePath: String,
) {
    init {
        require(tlName.isNotBlank()) { "Coverage TL name must not be blank" }
        require(qualifiedCodecName.isNotBlank()) { "Coverage codec name must not be blank for $tlName" }
        require(isStableRelativePath(codecRelativePath) && codecRelativePath.endsWith(".kt")) {
            "Codec path must be a stable relative Kotlin path for $tlName: $codecRelativePath"
        }
    }
}

class TlCodecCoverageExclusionEntry internal constructor(
    val declarationKind: TlDeclarationKind,
    val tlName: String,
    val constructorId: UInt,
    val reason: TlCodecExclusionReason,
) {
    init {
        require(tlName.isNotBlank()) { "D-023 exclusion TL name must not be blank" }
    }
}

internal fun createTlCodecCoverageReport(
    declarations: TlDeclarationGenerationResult,
    codecs: TlCodecGenerationResult,
    registries: TlRegistryGenerationResult,
): TlCodecCoverageReport {
    val declarationSchemas = uniqueBy(
        "declaration schema identity",
        declarations.symbolTable.schemas,
        { it.schema.key },
    )
    val codecSchemas = uniqueBy("codec schema identity", codecs.plan.schemas, TlSchemaCodecPlan::schemaKey)
    val coverageSchemas = uniqueBy("coverage schema identity", codecs.coverage.schemas, TlSchemaCodecCoverage::schemaKey)
    val registrySchemas = uniqueBy("registry schema identity", registries.plan.schemas, TlSchemaRegistryPlan::schemaKey)
    val schemaKeys = declarationSchemas.keys
    require(codecSchemas.keys == schemaKeys) { "Codec schema identities do not match declaration schema identities" }
    require(coverageSchemas.keys == schemaKeys) { "Coverage schema identities do not match declaration schema identities" }
    require(registrySchemas.keys == schemaKeys) { "Registry schema identities do not match declaration schema identities" }

    val schemas = schemaKeys.sortedWith(schemaKeyComparator).map { schemaKey ->
        val declarationSchema = declarationSchemas.getValue(schemaKey)
        val codecSchema = codecSchemas.getValue(schemaKey)
        val coverageSchema = coverageSchemas.getValue(schemaKey)
        val registrySchema = registrySchemas.getValue(schemaKey)
        createSchemaReport(declarationSchema, codecSchema, coverageSchema, registrySchema)
    }
    val report = TlCodecCoverageReport(TL_CODEC_COVERAGE_REPORT_FORMAT_VERSION, schemas)
    require(report.concreteConstructorCount == codecs.coverage.concreteConstructorCount) {
        "Serialized concrete constructor count does not match codec coverage metadata"
    }
    require(report.methodResultCount == codecs.coverage.methodResultCount) {
        "Serialized method-result count does not match codec coverage metadata"
    }
    require(report.d023ExclusionCount == codecs.coverage.excludedCount) {
        "Serialized D-023 exclusion count does not match codec coverage metadata"
    }
    require(report.concreteConstructorCount == registries.constructorCount) {
        "Serialized concrete constructor count does not match generated registries"
    }
    require(report.methodResultCount == registries.methodCount) {
        "Serialized method-result count does not match generated registries"
    }
    return report
}

private fun createSchemaReport(
    declarations: TlSchemaSymbols,
    codecs: TlSchemaCodecPlan,
    coverage: TlSchemaCodecCoverage,
    registry: TlSchemaRegistryPlan,
): TlCodecCoverageReportSchema {
    val schemaKey = declarations.schema.key
    require(codecs.schemaKey == schemaKey && coverage.schemaKey == schemaKey && registry.schemaKey == schemaKey) {
        "Coverage inputs must have one schema identity: $schemaKey"
    }
    require(codecs.registry == registry.contract) { "Codec and generated registry contracts differ for $schemaKey" }

    val constructors = codecs.constructors.sortedWith(declarationCodecComparator)
    val methods = codecs.methods.sortedWith(declarationCodecComparator)
    val resultsByMethod = uniqueBy("method-result TL name in $schemaKey", codecs.methodResults, TlMethodResultCodecPlan::methodTlName)
    val methodsByName = uniqueBy("method TL name in $schemaKey", methods, TlDeclarationCodecPlan::tlName)
    require(resultsByMethod.keys == methodsByName.keys) { "Every method must have exactly one result codec for $schemaKey" }

    requireCoverageMetadata(schemaKey, constructors, methods, codecs.methodResults, codecs.exclusions, coverage)
    requireRegistryCoverage(schemaKey, constructors, methods, registry)
    requireDeclarationCoverage(declarations, constructors, methods, codecs.exclusions)

    val constructorEntries = constructors.map { constructor ->
        TlCodecCoverageReportEntry(
            tlName = constructor.tlName,
            constructorId = constructor.constructorId,
            qualifiedCodecName = constructor.qualifiedCodecName,
            codecRelativePath = constructor.relativePath,
        )
    }.sortedWith(coverageEntryComparator)
    val methodResultEntries = resultsByMethod.values.map { result ->
        val method = methodsByName.getValue(result.methodTlName)
        TlCodecCoverageReportEntry(
            tlName = result.methodTlName,
            constructorId = method.constructorId,
            qualifiedCodecName = result.qualifiedName,
            codecRelativePath = method.relativePath,
        )
    }.sortedWith(coverageEntryComparator)
    val exclusionEntries = codecs.exclusions.map { exclusion ->
        TlCodecCoverageExclusionEntry(
            declarationKind = exclusion.declarationKind,
            tlName = exclusion.tlName,
            constructorId = exclusion.constructorId,
            reason = exclusion.reason,
        )
    }.sortedWith(exclusionEntryComparator)
    val provenance = declarations.schema.source.provenance
    return TlCodecCoverageReportSchema(
        schemaKey = schemaKey,
        snapshotRelativePath = provenance?.artifactRelativePath,
        sourceTlPath = provenance?.sourceTlPath,
        sourceSha256 = provenance?.sourceSha256,
        exportedJsonSha256 = provenance?.exportedJsonSha256,
        tellersCommit = provenance?.tellersCommit,
        registryQualifiedName = registry.contract.qualifiedName,
        registryRelativePath = registry.relativePath,
        concreteConstructors = constructorEntries,
        methodResults = methodResultEntries,
        d023Exclusions = exclusionEntries,
    )
}

private fun requireCoverageMetadata(
    schemaKey: TlSchemaKey,
    constructors: List<TlDeclarationCodecPlan>,
    methods: List<TlDeclarationCodecPlan>,
    methodResults: List<TlMethodResultCodecPlan>,
    exclusions: List<TlCodecExclusion>,
    coverage: TlSchemaCodecCoverage,
) {
    val expectedConstructors = constructors.map {
        TlCodecCoverageEntry(it.tlName, it.constructorId, it.qualifiedCodecName)
    }.sortedWith(inMemoryCoverageComparator)
    val actualConstructors = coverage.concreteConstructors.sortedWith(inMemoryCoverageComparator)
    require(actualConstructors == expectedConstructors) { "Concrete constructor coverage metadata differs for $schemaKey" }

    val methodIds = methods.associate { it.tlName to it.constructorId }
    val expectedResults = methodResults.map { result ->
        TlCodecCoverageEntry(result.methodTlName, methodIds.getValue(result.methodTlName), result.qualifiedName)
    }.sortedWith(inMemoryCoverageComparator)
    val actualResults = coverage.methodResults.sortedWith(inMemoryCoverageComparator)
    require(actualResults == expectedResults) { "Method-result coverage metadata differs for $schemaKey" }
    require(coverage.exclusions.sortedWith(codecExclusionComparator) == exclusions.sortedWith(codecExclusionComparator)) {
        "D-023 exclusion coverage metadata differs for $schemaKey"
    }
}

private fun requireRegistryCoverage(
    schemaKey: TlSchemaKey,
    constructors: List<TlDeclarationCodecPlan>,
    methods: List<TlDeclarationCodecPlan>,
    registry: TlSchemaRegistryPlan,
) {
    require(registry.constructors.sortedWith(declarationCodecComparator) == constructors) {
        "Registry constructor coverage differs for $schemaKey"
    }
    require(registry.methods.sortedWith(declarationCodecComparator) == methods) {
        "Registry method coverage differs for $schemaKey"
    }
}

private fun requireDeclarationCoverage(
    schema: TlSchemaSymbols,
    constructors: List<TlDeclarationCodecPlan>,
    methods: List<TlDeclarationCodecPlan>,
    exclusions: List<TlCodecExclusion>,
) {
    val sourceConstructors = schema.declarations.filter { it.source.kind == TlDeclarationKind.CONSTRUCTOR }
        .map { it.source.name to it.source.id }.toSet()
    val coveredConstructors = constructors.map { it.tlName to it.constructorId }.toSet() +
        exclusions.map { it.tlName to it.constructorId }
    require(sourceConstructors.size == constructors.size + exclusions.size && sourceConstructors == coveredConstructors) {
        "Concrete codecs and D-023 exclusions do not exactly cover constructors for ${schema.schema.key}"
    }
    val sourceMethods = schema.declarations.filter { it.source.kind == TlDeclarationKind.FUNCTION }
        .map { it.source.name to it.source.id }.toSet()
    val coveredMethods = methods.map { it.tlName to it.constructorId }.toSet()
    require(sourceMethods.size == methods.size && sourceMethods == coveredMethods) {
        "Generated method codecs do not exactly cover functions for ${schema.schema.key}"
    }
}

private fun StringBuilder.appendSchema(schema: TlCodecCoverageReportSchema) {
    line("    {")
    line("      \"schemaIdentity\": {")
    line("        \"kind\": ${jsonString(schema.schemaKey.kind.name.lowercase())},")
    line("        \"layer\": ${schema.schemaKey.layer ?: "null"}")
    line("      },")
    line("      \"snapshot\": {")
    line("        \"relativePath\": ${nullableJsonString(schema.snapshotRelativePath)},")
    line("        \"sourceTlPath\": ${nullableJsonString(schema.sourceTlPath)},")
    line("        \"sourceSha256\": ${nullableJsonString(schema.sourceSha256)},")
    line("        \"exportedJsonSha256\": ${nullableJsonString(schema.exportedJsonSha256)},")
    line("        \"tellersCommit\": ${nullableJsonString(schema.tellersCommit)}")
    line("      },")
    line("      \"registry\": {")
    line("        \"qualifiedName\": ${jsonString(schema.registryQualifiedName)},")
    line("        \"relativePath\": ${jsonString(schema.registryRelativePath)}")
    line("      },")
    line("      \"counts\": {")
    line("        \"concreteConstructors\": ${schema.concreteConstructors.size},")
    line("        \"methodResults\": ${schema.methodResults.size},")
    line("        \"d023Exclusions\": ${schema.d023Exclusions.size}")
    line("      },")
    appendCoverageEntries("concreteConstructors", schema.concreteConstructors)
    line(",")
    appendCoverageEntries("methodResults", schema.methodResults)
    line(",")
    line("      \"d023Exclusions\": [")
    schema.d023Exclusions.forEachIndexed { index, exclusion ->
        line("        {")
        line("          \"declarationKind\": ${jsonString(exclusion.declarationKind.name.lowercase())},")
        line("          \"tlName\": ${jsonString(exclusion.tlName)},")
        line("          \"constructorId\": ${exclusion.constructorId},")
        line("          \"constructorIdHex\": ${jsonString(constructorIdHex(exclusion.constructorId))},")
        line("          \"reason\": ${jsonString(exclusion.reason.name.lowercase())}")
        append("        }")
        if (index < schema.d023Exclusions.lastIndex) append(',')
        append('\n')
    }
    line("      ]")
    append("    }")
}

private fun StringBuilder.appendCoverageEntries(name: String, entries: List<TlCodecCoverageReportEntry>) {
    line("      ${jsonString(name)}: [")
    entries.forEachIndexed { index, entry ->
        line("        {")
        line("          \"tlName\": ${jsonString(entry.tlName)},")
        line("          \"constructorId\": ${entry.constructorId},")
        line("          \"constructorIdHex\": ${jsonString(constructorIdHex(entry.constructorId))},")
        line("          \"qualifiedCodecName\": ${jsonString(entry.qualifiedCodecName)},")
        line("          \"codecRelativePath\": ${jsonString(entry.codecRelativePath)}")
        append("        }")
        if (index < entries.lastIndex) append(',')
        append('\n')
    }
    append("      ]")
}

private fun StringBuilder.line(value: String) {
    append(value).append('\n')
}

private fun constructorIdHex(value: UInt): String = "0x${value.toString(16).padStart(8, '0')}u"

private fun nullableJsonString(value: String?): String = value?.let(::jsonString) ?: "null"

private fun jsonString(value: String): String = buildString {
    append('"')
    value.forEach { character ->
        when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000c' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (character.code !in 0x20..0x7e) {
                append("\\u").append(character.code.toString(16).padStart(4, '0'))
            } else {
                append(character)
            }
        }
    }
    append('"')
}

private fun isStableRelativePath(path: String): Boolean =
    path.isNotBlank() && !path.startsWith('/') && !path.startsWith('\\') && ':' !in path && '\\' !in path &&
        path.split('/').none { it.isEmpty() || it == "." || it == ".." }

private fun isLowerHexDigit(character: Char): Boolean = character in '0'..'9' || character in 'a'..'f'

private fun validateEntries(label: String, schemaKey: TlSchemaKey, entries: List<TlCodecCoverageReportEntry>) {
    requireUnique("$label TL name in $schemaKey", entries.map { it.tlName })
    requireUnique("$label UInt ID in $schemaKey", entries.map { it.constructorId })
    requireUnique("$label qualified codec name in $schemaKey", entries.map { it.qualifiedCodecName })
    requireUnique("$label codec relative path in $schemaKey", entries.map { it.codecRelativePath })
}

private fun <T> requireUnique(label: String, values: List<T>) {
    require(values.size == values.distinct().size) { "$label values must be unique" }
}

private fun <K, V> uniqueBy(label: String, values: List<V>, key: (V) -> K): Map<K, V> {
    val result = values.associateBy(key)
    require(result.size == values.size) { "$label values must be unique" }
    return result
}

private val schemaKeyComparator = compareBy<TlSchemaKey>({ it.kind.ordinal }, { it.layer ?: -1 })
private val schemaReportComparator = compareBy<TlCodecCoverageReportSchema>(
    { it.schemaKey.kind.ordinal },
    { it.schemaKey.layer ?: -1 },
)
private val declarationCodecComparator = compareBy<TlDeclarationCodecPlan>(
    { it.constructorId.toLong() },
    TlDeclarationCodecPlan::tlName,
    TlDeclarationCodecPlan::relativePath,
)
private val coverageEntryComparator = compareBy<TlCodecCoverageReportEntry>(
    { it.constructorId.toLong() },
    TlCodecCoverageReportEntry::tlName,
    TlCodecCoverageReportEntry::qualifiedCodecName,
)
private val inMemoryCoverageComparator = compareBy<TlCodecCoverageEntry>(
    { it.constructorId.toLong() },
    TlCodecCoverageEntry::tlName,
    TlCodecCoverageEntry::qualifiedCodecName,
)
private val codecExclusionComparator = compareBy<TlCodecExclusion>(
    { it.declarationKind.ordinal },
    { it.constructorId.toLong() },
    TlCodecExclusion::tlName,
    { it.reason.ordinal },
)
private val exclusionEntryComparator = compareBy<TlCodecCoverageExclusionEntry>(
    { it.declarationKind.ordinal },
    { it.constructorId.toLong() },
    TlCodecCoverageExclusionEntry::tlName,
    { it.reason.ordinal },
)
