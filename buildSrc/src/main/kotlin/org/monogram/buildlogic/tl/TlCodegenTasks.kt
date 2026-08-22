package org.monogram.buildlogic.tl

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.time.Duration
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

/** CLI entry points and positional argument order shared with the TL code generator. */
object TlCodegenCliContract {
    const val MAIN_CLASS = "org.monogram.tools.tl.codegen.gradle.TlCodegenTaskCli"

    fun validationArguments(manifest: File): List<String> =
        listOf("validate", manifest.absolutePath)

    fun generationArguments(manifest: File, outputDirectory: File): List<String> =
        listOf("generate", manifest.absolutePath, outputDirectory.absolutePath)

    fun verificationArguments(manifest: File, generatedSources: File): List<String> =
        listOf("verify", manifest.absolutePath, generatedSources.absolutePath)
}

abstract class AbstractTlSchemaCliTask : DefaultTask() {
    init {
        timeout.set(Duration.ofMinutes(20))
    }

    @get:Classpath
    abstract val cliClasspath: ConfigurableFileCollection

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val schemaManifest: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val schemaSnapshots: ConfigurableFileCollection

    @get:org.gradle.api.tasks.Input
    abstract val mainClass: Property<String>

    @get:Inject
    protected abstract val execOperations: ExecOperations

    protected fun runCli(
        arguments: List<String>,
        standardOutput: OutputStream? = null,
    ) {
        val configuredMainClass = mainClass.get()
        execOperations.javaexec {
            classpath(cliClasspath)
            mainClass.set(configuredMainClass)
            setArgs(arguments)
            if (standardOutput != null) {
                this.standardOutput = standardOutput
            }
        }
    }
}

@CacheableTask
abstract class ValidateTlSchemasTask : AbstractTlSchemaCliTask() {
    @get:OutputFile
    abstract val validationReport: RegularFileProperty

    init {
        mainClass.convention(TlCodegenCliContract.MAIN_CLASS)
    }

    @TaskAction
    fun validateSchemas() {
        val manifest = schemaManifest.get().asFile
        val report = validationReport.get().asFile
        report.parentFile.mkdirs()

        val output = ByteArrayOutputStream()
        runCli(
            arguments = TlCodegenCliContract.validationArguments(manifest),
            standardOutput = output,
        )
        report.writeText(output.toDeterministicText(), Charsets.UTF_8)
    }
}

@CacheableTask
abstract class GenerateTlKotlinTask : AbstractTlSchemaCliTask() {
    @get:OutputDirectory
    abstract val generatedSources: DirectoryProperty

    @get:OutputFile
    abstract val generationReport: RegularFileProperty

    init {
        mainClass.convention(TlCodegenCliContract.MAIN_CLASS)
    }

    @TaskAction
    fun generateSources() {
        val report = generationReport.get().asFile
        report.parentFile.mkdirs()
        val output = ByteArrayOutputStream()
        runCli(
            arguments = TlCodegenCliContract.generationArguments(
                manifest = schemaManifest.get().asFile,
                outputDirectory = generatedSources.get().asFile,
            ),
            standardOutput = output,
        )
        report.writeText(output.toDeterministicText(), Charsets.UTF_8)
    }
}

@CacheableTask
abstract class VerifyGeneratedTlKotlinTask : AbstractTlSchemaCliTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val generatedSources: DirectoryProperty

    @get:OutputFile
    abstract val verificationReport: RegularFileProperty

    init {
        mainClass.convention(TlCodegenCliContract.MAIN_CLASS)
    }

    @TaskAction
    fun verifySources() {
        val report = verificationReport.get().asFile
        report.parentFile.mkdirs()
        val output = ByteArrayOutputStream()
        runCli(
            arguments = TlCodegenCliContract.verificationArguments(
                manifest = schemaManifest.get().asFile,
                generatedSources = generatedSources.get().asFile,
            ),
            standardOutput = output,
        )
        report.writeText(output.toDeterministicText(), Charsets.UTF_8)
    }
}

private fun ByteArrayOutputStream.toDeterministicText(): String =
    toString(Charsets.UTF_8.name())
        .replace("\r\n", "\n")
        .replace('\r', '\n')
