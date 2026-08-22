package org.monogram.tools.tl.codegen.gradle

import org.monogram.tools.tl.codegen.emit.codec.TlKotlinSourceGenerator
import org.monogram.tools.tl.codegen.emit.declaration.TlDeclarationGenerator
import org.monogram.tools.tl.codegen.model.TlSchemaKind
import org.monogram.tools.tl.codegen.model.ValidatedTlSchema
import org.monogram.tools.tl.codegen.validation.SchemaValidationException
import org.monogram.tools.tl.codegen.validation.TlSchemaDocumentReader
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileVisitOption
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Comparator

/** Stable, task-facing operations for the pinned TL schema corpus. */
class TlCodegenTaskApi internal constructor(
    private val pipeline: TlCodegenPipeline,
) {
    constructor() : this(DefaultTlCodegenPipeline)

    fun validate(manifestPath: Path): TlCodegenTaskReport {
        val validation = loadValidation(manifestPath)
        return validation.report(TlCodegenTaskOperation.VALIDATE, TlCodegenTaskStatus.VALID)
    }

    fun generate(manifestPath: Path, outputDirectory: Path): TlCodegenTaskReport {
        val generated = loadGenerated(manifestPath)
        val outputs = validateOutputs(generated.outputs)
        replaceOutput(outputDirectory, outputs)
        return generated.validation.report(
            operation = TlCodegenTaskOperation.GENERATE,
            status = TlCodegenTaskStatus.GENERATED,
            outputs = outputs,
        )
    }

    fun verify(manifestPath: Path, outputDirectory: Path): TlCodegenTaskReport {
        val generated = loadGenerated(manifestPath)
        val outputs = validateOutputs(generated.outputs)
        verifyOutput(outputDirectory, outputs)
        return generated.validation.report(
            operation = TlCodegenTaskOperation.VERIFY,
            status = TlCodegenTaskStatus.VERIFIED,
            outputs = outputs,
        )
    }

    private fun loadValidation(manifestPath: Path): TlValidationSummary = try {
        pipeline.validate(manifestPath)
    } catch (error: TlCodegenTaskException) {
        throw error
    } catch (error: SchemaValidationException) {
        throw validationFailure(error)
    } catch (error: RuntimeException) {
        throw TlCodegenTaskException(
            TlCodegenTaskFailure.VALIDATION,
            "input-unreadable:${error::class.simpleName ?: "RuntimeException"}",
            error,
        )
    }

    private fun loadGenerated(manifestPath: Path): TlGeneratedCorpus = try {
        pipeline.generate(manifestPath)
    } catch (error: TlCodegenTaskException) {
        throw error
    } catch (error: SchemaValidationException) {
        throw validationFailure(error)
    } catch (error: RuntimeException) {
        throw TlCodegenTaskException(
            TlCodegenTaskFailure.GENERATION,
            "generator-failed:${error::class.simpleName ?: "RuntimeException"}",
            error,
        )
    }
}

enum class TlCodegenTaskOperation {
    VALIDATE,
    GENERATE,
    VERIFY,
}

enum class TlCodegenTaskStatus {
    VALID,
    GENERATED,
    VERIFIED,
}

data class TlCodegenSchemaReport(
    val kind: String,
    val layer: Int?,
    val constructors: Int,
    val functions: Int,
    val sourceSha256: String?,
    val snapshotSha256: String?,
)

data class TlCodegenTaskReport(
    val operation: TlCodegenTaskOperation,
    val status: TlCodegenTaskStatus,
    val schemas: List<TlCodegenSchemaReport>,
    val outputPaths: List<String> = emptyList(),
    val outputBytes: Long = 0,
) {
    init {
        require(outputPaths == outputPaths.distinct().sorted()) { "Output report paths must be unique and sorted" }
    }

    fun toDeterministicJson(): String = buildString {
        appendLine("{")
        appendLine("  \"formatVersion\": 1,")
        appendLine("  \"operation\": ${jsonString(operation.name.lowercase())},")
        appendLine("  \"status\": ${jsonString(status.name.lowercase())},")
        appendLine("  \"schemas\": [")
        schemas.forEachIndexed { index, schema ->
            appendLine("    {")
            appendLine("      \"kind\": ${jsonString(schema.kind)},")
            appendLine("      \"layer\": ${schema.layer ?: "null"},")
            appendLine("      \"constructors\": ${schema.constructors},")
            appendLine("      \"functions\": ${schema.functions},")
            appendLine("      \"sourceSha256\": ${schema.sourceSha256?.let(::jsonString) ?: "null"},")
            appendLine("      \"snapshotSha256\": ${schema.snapshotSha256?.let(::jsonString) ?: "null"}")
            append("    }")
            if (index < schemas.lastIndex) append(',')
            appendLine()
        }
        appendLine("  ],")
        appendLine("  \"outputs\": [")
        outputPaths.forEachIndexed { index, path ->
            append("    ").append(jsonString(path))
            if (index < outputPaths.lastIndex) append(',')
            appendLine()
        }
        appendLine("  ],")
        appendLine("  \"outputBytes\": $outputBytes")
        appendLine("}")
    }
}

enum class TlCodegenTaskFailure {
    VALIDATION,
    GENERATION,
    UNSAFE_PATH,
    IO,
    OUTPUT_MISMATCH,
}

class TlCodegenTaskException(
    val failure: TlCodegenTaskFailure,
    val detail: String,
    cause: Throwable? = null,
) : RuntimeException("${failure.name}: $detail", cause)

internal data class TlValidationSummary(
    val schemas: List<TlCodegenSchemaReport>,
) {
    init {
        require(schemas.isNotEmpty()) { "At least one schema is required" }
    }

    fun report(
        operation: TlCodegenTaskOperation,
        status: TlCodegenTaskStatus,
        outputs: Map<String, ByteArray> = emptyMap(),
    ): TlCodegenTaskReport = TlCodegenTaskReport(
        operation = operation,
        status = status,
        schemas = schemas,
        outputPaths = outputs.keys.sorted(),
        outputBytes = outputs.values.sumOf { it.size.toLong() },
    )
}

internal data class TlGeneratedCorpus(
    val validation: TlValidationSummary,
    val outputs: Map<String, ByteArray>,
)

internal interface TlCodegenPipeline {
    fun validate(manifestPath: Path): TlValidationSummary
    fun generate(manifestPath: Path): TlGeneratedCorpus
}

private object DefaultTlCodegenPipeline : TlCodegenPipeline {
    override fun validate(manifestPath: Path): TlValidationSummary =
        TlValidationSummary(readSchemas(manifestPath).toSchemaReports())

    override fun generate(manifestPath: Path): TlGeneratedCorpus {
        val schemas = readSchemas(manifestPath)
        val declarations = TlDeclarationGenerator().generate(schemas)
        val generated = TlKotlinSourceGenerator().generate(declarations)
        return TlGeneratedCorpus(
            validation = TlValidationSummary(schemas.toSchemaReports()),
            outputs = generated.allOutputBytes(),
        )
    }

    private fun readSchemas(manifestPath: Path): List<ValidatedTlSchema> =
        TlSchemaDocumentReader.readManifest(manifestPath)
}

private fun List<ValidatedTlSchema>.toSchemaReports(): List<TlCodegenSchemaReport> =
    sortedWith(compareBy({ it.key.kind.ordinal }, { it.key.layer ?: -1 })).map { schema ->
        TlCodegenSchemaReport(
            kind = schema.key.kind.name.lowercase(),
            layer = schema.key.layer,
            constructors = schema.constructors.size,
            functions = schema.functions.size,
            sourceSha256 = schema.source.provenance?.sourceSha256,
            snapshotSha256 = schema.source.provenance?.exportedJsonSha256,
        )
    }

private fun validationFailure(error: SchemaValidationException): TlCodegenTaskException {
    val schema = error.schemaKey?.let { key ->
        val layer = if (key.kind == TlSchemaKind.TRANSPORT) "none" else key.layer.toString()
        ":${key.kind.name.lowercase()}:$layer"
    }.orEmpty()
    val declaration = error.declarationName?.let { ":$it" }.orEmpty()
    return TlCodegenTaskException(
        TlCodegenTaskFailure.VALIDATION,
        "${error.reason.name}:${error.location}$schema$declaration",
        error,
    )
}

private val SAFE_SEGMENT = Regex("[A-Za-z0-9._-]+")

private fun validateOutputs(outputs: Map<String, ByteArray>): Map<String, ByteArray> {
    if (outputs.isEmpty()) {
        throw TlCodegenTaskException(TlCodegenTaskFailure.GENERATION, "empty-output")
    }
    val sorted = outputs.toSortedMap()
    sorted.forEach { (relativePath, bytes) ->
        validateRelativePath(relativePath)
        validateCanonicalUtf8(relativePath, bytes)
    }
    val paths = sorted.keys.toList()
    paths.zipWithNext().firstOrNull { (path, next) -> next.startsWith("$path/") }?.let { (path, _) ->
        throw TlCodegenTaskException(TlCodegenTaskFailure.UNSAFE_PATH, "file-directory-collision:$path")
    }
    return sorted.mapValuesTo(sortedMapOf()) { (_, bytes) -> bytes.copyOf() }
}

private fun validateRelativePath(relativePath: String) {
    val segments = relativePath.split('/')
    val safe = relativePath.isNotEmpty() &&
        !relativePath.startsWith('/') &&
        !relativePath.contains('\\') &&
        segments.all { segment -> segment !in setOf("", ".", "..") && SAFE_SEGMENT.matches(segment) }
    if (!safe) {
        throw TlCodegenTaskException(TlCodegenTaskFailure.UNSAFE_PATH, "invalid-output-path:$relativePath")
    }
    val parsed = try {
        Path.of(relativePath)
    } catch (error: RuntimeException) {
        throw TlCodegenTaskException(TlCodegenTaskFailure.UNSAFE_PATH, "invalid-output-path:$relativePath", error)
    }
    if (parsed.isAbsolute || parsed.normalize().toString().replace('\\', '/') != relativePath) {
        throw TlCodegenTaskException(TlCodegenTaskFailure.UNSAFE_PATH, "invalid-output-path:$relativePath")
    }
}

private fun validateCanonicalUtf8(relativePath: String, bytes: ByteArray) {
    val decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    val text = try {
        decoder.decode(ByteBuffer.wrap(bytes)).toString()
    } catch (error: CharacterCodingException) {
        throw TlCodegenTaskException(TlCodegenTaskFailure.GENERATION, "invalid-utf8:$relativePath", error)
    }
    if ('\r' in text || !text.endsWith('\n')) {
        throw TlCodegenTaskException(TlCodegenTaskFailure.GENERATION, "noncanonical-line-endings:$relativePath")
    }
}

private fun replaceOutput(outputDirectory: Path, outputs: Map<String, ByteArray>) {
    val output = outputDirectory.toAbsolutePath().normalize()
    val parent = output.parent
        ?: throw TlCodegenTaskException(TlCodegenTaskFailure.UNSAFE_PATH, "output-has-no-parent")
    try {
        Files.createDirectories(parent)
    } catch (error: IOException) {
        throw TlCodegenTaskException(TlCodegenTaskFailure.IO, "output-parent-create-failed", error)
    }
    if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(parent)) {
        throw TlCodegenTaskException(TlCodegenTaskFailure.UNSAFE_PATH, "unsafe-output-parent")
    }
    if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
        validateExistingTree(output)
    }

    val stage = try {
        Files.createTempDirectory(parent, ".${output.fileName}.stage-")
    } catch (error: IOException) {
        throw TlCodegenTaskException(TlCodegenTaskFailure.IO, "stage-create-failed", error)
    }
    var backup: Path? = null
    try {
        writeStage(stage, outputs)
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
            val backupPath = reserveSibling(parent, ".${output.fileName}.backup-")
            moveDirectory(output, backupPath)
            backup = backupPath
        }
        try {
            moveDirectory(stage, output)
        } catch (replaceError: IOException) {
            val backupPath = backup
            if (backupPath != null && !Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    moveDirectory(backupPath, output)
                    backup = null
                } catch (restoreError: IOException) {
                    replaceError.addSuppressed(restoreError)
                    throw TlCodegenTaskException(TlCodegenTaskFailure.IO, "replace-and-restore-failed", replaceError)
                }
            }
            throw TlCodegenTaskException(TlCodegenTaskFailure.IO, "replace-failed", replaceError)
        }
        backup?.let(::deleteTreeBestEffort)
        backup = null
    } catch (error: TlCodegenTaskException) {
        throw error
    } catch (error: IOException) {
        throw TlCodegenTaskException(TlCodegenTaskFailure.IO, "output-write-failed", error)
    } finally {
        deleteTreeBestEffort(stage)
        val stranded = backup
        if (stranded != null) {
            if (!Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    moveDirectory(stranded, output)
                } catch (_: IOException) {
                    // Leave the backup intact when restoration is not possible.
                }
            }
            if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
                deleteTreeBestEffort(stranded)
            }
        }
    }
}

private fun writeStage(stage: Path, outputs: Map<String, ByteArray>) {
    outputs.forEach { (relativePath, bytes) ->
        val target = stage.resolve(relativePath).normalize()
        if (!target.startsWith(stage)) {
            throw TlCodegenTaskException(TlCodegenTaskFailure.UNSAFE_PATH, "path-escaped-stage:$relativePath")
        }
        Files.createDirectories(target.parent)
        if (Files.isSymbolicLink(target.parent)) {
            throw TlCodegenTaskException(TlCodegenTaskFailure.UNSAFE_PATH, "symlink-in-stage:$relativePath")
        }
        Files.write(target, bytes)
    }
}

private fun reserveSibling(parent: Path, prefix: String): Path {
    val reserved = Files.createTempDirectory(parent, prefix)
    Files.delete(reserved)
    return reserved
}

@Throws(IOException::class)
private fun moveDirectory(source: Path, target: Path) {
    try {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(source, target)
    }
}

private fun verifyOutput(outputDirectory: Path, expected: Map<String, ByteArray>) {
    val output = outputDirectory.toAbsolutePath().normalize()
    if (!Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
        throw mismatch(expected.keys.toList(), emptyList(), emptyList())
    }
    validateExistingTree(output)
    val actual = readOutputFiles(output)
    val missing = (expected.keys - actual.keys).sorted()
    val extra = (actual.keys - expected.keys).sorted()
    val changed = (expected.keys intersect actual.keys).filter { path ->
        !expected.getValue(path).contentEquals(actual.getValue(path))
    }.sorted()
    if (missing.isNotEmpty() || extra.isNotEmpty() || changed.isNotEmpty()) {
        throw mismatch(missing, extra, changed)
    }
}

private fun readOutputFiles(root: Path): Map<String, ByteArray> = try {
    Files.walk(root, 128, *emptyArray<FileVisitOption>()).use { paths ->
        paths.filter { path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) }
            .map { path -> root.relativize(path).toString().replace('\\', '/') to Files.readAllBytes(path) }
            .toList()
            .toMap()
            .toSortedMap()
    }
} catch (error: IOException) {
    throw TlCodegenTaskException(TlCodegenTaskFailure.IO, "output-read-failed", error)
}

private fun validateExistingTree(root: Path) {
    if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
        throw TlCodegenTaskException(TlCodegenTaskFailure.UNSAFE_PATH, "output-is-not-directory")
    }
    try {
        Files.walk(root, 128, *emptyArray<FileVisitOption>()).use { paths ->
            paths.forEach { path ->
                if (Files.isSymbolicLink(path)) {
                    val relative = root.relativize(path).toString().replace('\\', '/')
                    throw TlCodegenTaskException(TlCodegenTaskFailure.UNSAFE_PATH, "symlink-in-output:$relative")
                }
                if (path != root &&
                    !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) &&
                    !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                ) {
                    val relative = root.relativize(path).toString().replace('\\', '/')
                    throw TlCodegenTaskException(TlCodegenTaskFailure.UNSAFE_PATH, "special-file-in-output:$relative")
                }
            }
        }
    } catch (error: TlCodegenTaskException) {
        throw error
    } catch (error: IOException) {
        throw TlCodegenTaskException(TlCodegenTaskFailure.IO, "output-scan-failed", error)
    }
}

private fun mismatch(missing: List<String>, extra: List<String>, changed: List<String>): TlCodegenTaskException =
    TlCodegenTaskException(
        TlCodegenTaskFailure.OUTPUT_MISMATCH,
        "missing=${missing.joinToString(",")};extra=${extra.joinToString(",")};changed=${changed.joinToString(",")}",
    )

private fun deleteTreeBestEffort(root: Path) {
    if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
    try {
        Files.walk(root, 128, *emptyArray<FileVisitOption>()).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    } catch (_: IOException) {
        // Cleanup must not replace the operation's primary result.
    }
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
