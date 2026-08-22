package org.monogram.tools.tl.codegen.emit.declaration

import org.monogram.tools.tl.codegen.model.TlDeclarationKind
import org.monogram.tools.tl.codegen.model.TlSchemaKey
import org.monogram.tools.tl.codegen.naming.KotlinNameCollision
import org.monogram.tools.tl.codegen.naming.TlSymbolTable

const val DECLARATION_MANIFEST_PATH: String = "tl-declaration-manifest.json"
const val COLLISION_REPORT_PATH: String = "tl-name-collisions.json"

data class GeneratedKotlinFile(
    val relativePath: String,
    val packageName: String,
    val declarations: List<String>,
    val content: String,
)

data class TlOutputManifestEntry(
    val schemaKey: TlSchemaKey,
    val tlName: String,
    val kotlinName: String,
    val kind: TlDeclarationKind,
    val constructorId: UInt,
    val constructorIdHex: String,
    val sourceSchemaHash: String?,
    val relativePath: String,
    val partitionRelativePath: String,
)

data class TlOutputManifest(
    val files: List<String>,
    val declarations: List<TlOutputManifestEntry>,
) {
    init {
        require(files == files.distinct().sorted()) { "Manifest files must be unique and sorted" }
        require(
            declarations.size == declarations.distinctBy {
                listOf(it.schemaKey, it.kind, it.tlName, it.constructorId)
            }.size,
        ) {
            "Manifest declaration identities must be unique within schema keys"
        }
    }

    fun toDeterministicJson(): String = buildString {
        appendLine("{")
        appendLine("  \"formatVersion\": 1,")
        appendLine("  \"files\": [")
        files.forEachIndexed { index, path ->
            append("    ").append(jsonString(path))
            if (index < files.lastIndex) append(',')
            appendLine()
        }
        appendLine("  ],")
        appendLine("  \"declarations\": [")
        declarations.forEachIndexed { index, entry ->
            appendLine("    {")
            appendLine("      \"schemaKey\": {")
            appendLine("        \"kind\": ${jsonString(entry.schemaKey.kind.name.lowercase())},")
            appendLine("        \"layer\": ${entry.schemaKey.layer ?: "null"}")
            appendLine("      },")
            appendLine("      \"tlName\": ${jsonString(entry.tlName)},")
            appendLine("      \"kotlinName\": ${jsonString(entry.kotlinName)},")
            appendLine("      \"kind\": ${jsonString(entry.kind.name.lowercase())},")
            appendLine("      \"constructorId\": ${entry.constructorId},")
            appendLine("      \"constructorIdHex\": ${jsonString("0x${entry.constructorIdHex.lowercase()}u")},")
            appendLine("      \"sourceSchemaHash\": ${entry.sourceSchemaHash?.let(::jsonString) ?: "null"},")
            appendLine("      \"relativePath\": ${jsonString(entry.relativePath)},")
            appendLine("      \"partitionRelativePath\": ${jsonString(entry.partitionRelativePath)}")
            append("    }")
            if (index < declarations.lastIndex) append(',')
            appendLine()
        }
        appendLine("  ]")
        appendLine("}")
    }
}

data class TlDeclarationGenerationResult(
    val files: List<GeneratedKotlinFile>,
    val manifest: TlOutputManifest,
    val symbolTable: TlSymbolTable,
    val collisions: List<KotlinNameCollision>,
) {
    val manifestJson: String get() = manifest.toDeterministicJson()
    val collisionReportJson: String get() = collisions.toDeterministicJson()

    fun allOutputBytes(): Map<String, ByteArray> = buildMap {
        files.forEach { put(it.relativePath, it.content.toByteArray(Charsets.UTF_8)) }
        put(DECLARATION_MANIFEST_PATH, manifestJson.toByteArray(Charsets.UTF_8))
        put(COLLISION_REPORT_PATH, collisionReportJson.toByteArray(Charsets.UTF_8))
    }.toSortedMap()
}

fun List<KotlinNameCollision>.toDeterministicJson(): String = buildString {
    val sorted = sortedWith(compareBy(KotlinNameCollision::scope, KotlinNameCollision::preferredName))
    appendLine("{")
    appendLine("  \"formatVersion\": 1,")
    appendLine("  \"collisions\": [")
    sorted.forEachIndexed { collisionIndex, collision ->
        appendLine("    {")
        appendLine("      \"scope\": ${jsonString(collision.scope)},")
        appendLine("      \"preferredName\": ${jsonString(collision.preferredName)},")
        appendLine("      \"allocations\": [")
        collision.allocations.sortedBy { it.request.identity }.forEachIndexed { allocationIndex, allocation ->
            appendLine("        {")
            appendLine("          \"identity\": ${jsonString(allocation.request.identity)},")
            appendLine("          \"sourceName\": ${jsonString(allocation.request.sourceName)},")
            appendLine("          \"allocatedName\": ${jsonString(allocation.allocatedName)}")
            append("        }")
            if (allocationIndex < collision.allocations.lastIndex) append(',')
            appendLine()
        }
        appendLine("      ]")
        append("    }")
        if (collisionIndex < sorted.lastIndex) append(',')
        appendLine()
    }
    appendLine("  ]")
    appendLine("}")
}

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
            else -> if (character.code < 0x20) append("\\u%04x".format(character.code)) else append(character)
        }
    }
    append('"')
}
