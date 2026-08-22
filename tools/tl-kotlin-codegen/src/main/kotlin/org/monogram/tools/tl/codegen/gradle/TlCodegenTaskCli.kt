package org.monogram.tools.tl.codegen.gradle

import java.io.PrintStream
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlin.system.exitProcess

object TlCodegenTaskCli {
    const val EXIT_SUCCESS: Int = 0
    const val EXIT_USAGE: Int = 2
    const val EXIT_VALIDATION: Int = 3
    const val EXIT_GENERATION: Int = 4
    const val EXIT_UNSAFE_PATH: Int = 5
    const val EXIT_IO: Int = 6
    const val EXIT_OUTPUT_MISMATCH: Int = 7

    @JvmStatic
    fun main(args: Array<String>) {
        exitProcess(run(args))
    }

    internal fun run(
        args: Array<String>,
        api: TlCodegenTaskApi = TlCodegenTaskApi(),
        out: PrintStream = System.out,
        err: PrintStream = System.err,
    ): Int {
        val operation = args.firstOrNull()
        val expectedArguments = when (operation) {
            "validate" -> 2
            "generate", "verify" -> 3
            else -> return usage(err)
        }
        if (args.size != expectedArguments) return usage(err)

        return try {
            val manifest = Path.of(args[1])
            val report = when (operation) {
                "validate" -> api.validate(manifest)
                "generate" -> api.generate(manifest, Path.of(args[2]))
                "verify" -> api.verify(manifest, Path.of(args[2]))
                else -> error("unreachable")
            }
            out.print(report.toDeterministicJson())
            EXIT_SUCCESS
        } catch (error: InvalidPathException) {
            err.println("UNSAFE_PATH: invalid-cli-path")
            EXIT_UNSAFE_PATH
        } catch (error: TlCodegenTaskException) {
            err.println(error.message)
            when (error.failure) {
                TlCodegenTaskFailure.VALIDATION -> EXIT_VALIDATION
                TlCodegenTaskFailure.GENERATION -> EXIT_GENERATION
                TlCodegenTaskFailure.UNSAFE_PATH -> EXIT_UNSAFE_PATH
                TlCodegenTaskFailure.IO -> EXIT_IO
                TlCodegenTaskFailure.OUTPUT_MISMATCH -> EXIT_OUTPUT_MISMATCH
            }
        }
    }

    private fun usage(err: PrintStream): Int {
        err.println("usage: tl-codegen <validate manifest.json|generate manifest.json output-dir|verify manifest.json output-dir>")
        return EXIT_USAGE
    }
}
