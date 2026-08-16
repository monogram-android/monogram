package org.monogram.tools.tl.codegen.emit.declaration

import org.monogram.tools.tl.codegen.model.ValidatedTlSchema
import org.monogram.tools.tl.codegen.naming.TlSymbolTableBuilder

class TlDeclarationGenerator(
    private val symbolTableBuilder: TlSymbolTableBuilder = TlSymbolTableBuilder(),
) {
    fun generate(schemas: Collection<ValidatedTlSchema>): TlDeclarationGenerationResult {
        val symbols = symbolTableBuilder.build(schemas)
        val emitter = TlDeclarationEmitter(symbols)
        val files = buildList {
            symbols.resultFamilies.forEach { family ->
                add(
                    GeneratedKotlinFile(
                        relativePath = family.relativePath,
                        packageName = family.packageName,
                        declarations = listOf(family.kotlinName),
                        content = emitter.emitFamily(family),
                    ),
                )
            }
            symbols.declarations.forEach { declaration ->
                add(
                    GeneratedKotlinFile(
                        relativePath = declaration.relativePath,
                        packageName = declaration.packageName,
                        declarations = listOf(declaration.kotlinName),
                        content = emitter.emitDeclaration(declaration),
                    ),
                )
            }
        }.sortedBy(GeneratedKotlinFile::relativePath)

        require(files.map(GeneratedKotlinFile::relativePath).distinct().size == files.size) {
            "Generated output paths must be unique"
        }

        val entries = symbols.declarations.map { declaration ->
            TlOutputManifestEntry(
                schemaKey = declaration.schema.key,
                tlName = declaration.source.name,
                kotlinName = declaration.kotlinName,
                kind = declaration.source.kind,
                constructorId = declaration.source.id,
                constructorIdHex = declaration.source.idHex,
                sourceSchemaHash = declaration.schema.source.provenance?.sourceSha256,
                relativePath = declaration.relativePath,
                partitionRelativePath = declaration.partitionRelativePath,
            )
        }.sortedWith(
            compareBy<TlOutputManifestEntry>(
                TlOutputManifestEntry::relativePath,
                { it.kind.ordinal },
                TlOutputManifestEntry::tlName,
                { it.constructorId.toLong() },
            ),
        )
        val manifest = TlOutputManifest(files.map(GeneratedKotlinFile::relativePath), entries)
        check(entries.size == schemas.sumOf { it.declarations.size }) {
            "Every input declaration must appear exactly once in the output manifest"
        }
        return TlDeclarationGenerationResult(
            files = files,
            manifest = manifest,
            symbolTable = symbols,
            collisions = symbols.collisionReport,
        )
    }
}
