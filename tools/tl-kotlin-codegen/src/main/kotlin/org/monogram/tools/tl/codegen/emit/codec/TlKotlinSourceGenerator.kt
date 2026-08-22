package org.monogram.tools.tl.codegen.emit.codec

import org.monogram.tools.tl.codegen.emit.declaration.COLLISION_REPORT_PATH
import org.monogram.tools.tl.codegen.emit.declaration.DECLARATION_MANIFEST_PATH
import org.monogram.tools.tl.codegen.emit.declaration.GeneratedKotlinFile
import org.monogram.tools.tl.codegen.emit.declaration.TlDeclarationGenerationResult
import org.monogram.tools.tl.codegen.emit.registry.TlRegistryGenerationResult
import org.monogram.tools.tl.codegen.emit.registry.TlRegistryGenerator

class TlKotlinSourceGenerator(
    private val codecGenerator: TlCodecGenerator = TlCodecGenerator(),
    private val registryGenerator: TlRegistryGenerator = TlRegistryGenerator(),
) {
    fun generate(declarations: TlDeclarationGenerationResult): TlKotlinSourceGenerationResult {
        val codecs = codecGenerator.generate(declarations)
        val registries = registryGenerator.generate(codecs)
        val files = (declarations.files + codecs.files + registries.files).sortedBy(GeneratedKotlinFile::relativePath)
        require(files.map(GeneratedKotlinFile::relativePath).distinct().size == files.size) {
            "Generated declaration, codec, and registry paths must be unique"
        }
        val manifest = declarations.manifest.copy(files = files.map(GeneratedKotlinFile::relativePath))
        return TlKotlinSourceGenerationResult(declarations, codecs, registries, files, manifest.toDeterministicJson())
    }
}

data class TlKotlinSourceGenerationResult(
    val declarations: TlDeclarationGenerationResult,
    val codecs: TlCodecGenerationResult,
    val registries: TlRegistryGenerationResult,
    val files: List<GeneratedKotlinFile>,
    val manifestJson: String,
) {
    val collisionReportJson: String get() = declarations.collisionReportJson
    val coverageReport: TlCodecCoverageReport by lazy(LazyThreadSafetyMode.NONE) {
        createTlCodecCoverageReport(declarations, codecs, registries)
    }
    val coverageReportJson: String get() = coverageReport.toDeterministicJson()

    fun allOutputBytes(): Map<String, ByteArray> = buildMap {
        files.forEach { put(it.relativePath, it.content.toByteArray(Charsets.UTF_8)) }
        put(DECLARATION_MANIFEST_PATH, manifestJson.toByteArray(Charsets.UTF_8))
        put(COLLISION_REPORT_PATH, collisionReportJson.toByteArray(Charsets.UTF_8))
        put(TL_CODEC_COVERAGE_REPORT_PATH, coverageReportJson.toByteArray(Charsets.UTF_8))
    }.toSortedMap()
}
