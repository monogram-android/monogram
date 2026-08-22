package org.monogram.tools.tl.codegen.validation

import java.nio.file.Path
import kotlin.io.path.name
import kotlin.system.exitProcess

object TlSchemaValidationCli {
    @JvmStatic
    fun main(args: Array<String>) {
        exitProcess(run(args))
    }

    internal fun run(args: Array<String>): Int {
        if (args.size != 1) {
            System.err.println("usage: tl-schema-validate <schema.json|manifest.json>")
            return 2
        }
        return try {
            val path = Path.of(args.single())
            val schemas = if (path.name == "manifest.json") {
                TlSchemaDocumentReader.readManifest(path)
            } else {
                listOf(TlSchemaDocumentReader.read(path))
            }
            schemas.forEach { schema ->
                println("${schema.key} constructors=${schema.constructors.size} functions=${schema.functions.size}")
            }
            0
        } catch (error: SchemaValidationException) {
            System.err.println(error.message)
            1
        } catch (error: RuntimeException) {
            System.err.println(error.message ?: error::class.simpleName)
            2
        }
    }
}
