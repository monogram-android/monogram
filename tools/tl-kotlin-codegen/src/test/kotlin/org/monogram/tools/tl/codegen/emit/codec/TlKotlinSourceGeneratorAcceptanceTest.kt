package org.monogram.tools.tl.codegen.emit.codec

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import org.monogram.tools.tl.codegen.emit.declaration.COLLISION_REPORT_PATH
import org.monogram.tools.tl.codegen.emit.declaration.DECLARATION_MANIFEST_PATH
import org.monogram.tools.tl.codegen.emit.declaration.GeneratedKotlinFile
import org.monogram.tools.tl.codegen.emit.declaration.TlDeclarationGenerator
import org.monogram.tools.tl.codegen.model.TlSchemaKey
import org.monogram.tools.tl.codegen.model.TlSchemaKind
import org.monogram.tools.tl.codegen.validation.TlSchemaDocumentReader

@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class TlKotlinSourceGeneratorAcceptanceTest {
    @Test
    fun `acceptance 1 all outputs are deterministic complete and in memory`() {
        requireCampaignTime("acceptance 1 start")
        val guardedBefore = guardedWorkspaceSnapshot()
        val buildBefore = generatedBuildOutputSnapshot()
        val first = generated
        val second = generateCorpus()

        assertByteMapsEqual(first.allOutputBytes(), second.allOutputBytes())
        assertEquals(EXPECTED_SCHEMA_KEYS, first.declarations.symbolTable.schemas.map { it.schema.key }.toSet())
        assertEquals(EXPECTED_SCHEMA_KEYS, first.codecs.coverage.schemas.map { it.schemaKey }.toSet())
        assertEquals(EXPECTED_SCHEMA_KEYS, first.registries.plan.schemas.map { it.schemaKey }.toSet())

        assertEquals(2_220, first.codecs.coverage.concreteConstructorCount)
        assertEquals(2_220, first.registries.constructorCount)
        assertEquals(766, first.codecs.coverage.methodResultCount)
        assertEquals(766, first.registries.methodCount)
        assertEquals(8, first.codecs.coverage.excludedCount)
        assertEquals(8, first.codecs.plan.exclusions.size)
        assertEquals(4, first.codecs.plan.exclusions.count { it.reason == TlCodecExclusionReason.BUILTIN_PRIMITIVE })
        assertEquals(2, first.codecs.plan.exclusions.count { it.reason == TlCodecExclusionReason.BUILTIN_VECTOR })
        assertEquals(2, first.codecs.plan.exclusions.count { it.reason == TlCodecExclusionReason.FORMAL_REPETITION })
        assertEquals(2_994, first.declarations.manifest.declarations.size)

        val declarationPaths = first.declarations.files.map(GeneratedKotlinFile::relativePath)
        val codecPaths = first.codecs.files.map(GeneratedKotlinFile::relativePath)
        val registryPaths = first.registries.files.map(GeneratedKotlinFile::relativePath)
        val expectedKotlinPaths = declarationPaths + codecPaths + registryPaths
        assertEquals(expectedKotlinPaths.size, expectedKotlinPaths.distinct().size)
        assertEquals(expectedKotlinPaths.sorted(), first.files.map(GeneratedKotlinFile::relativePath))
        assertTrue(first.files.all { it.relativePath.endsWith(".kt") })
        assertEquals(14, registryPaths.size)

        val manifest = Json.parseToJsonElement(first.manifestJson).jsonObject
        assertEquals(1, manifest.getValue("formatVersion").jsonPrimitive.content.toInt())
        val manifestFiles = manifest.getValue("files").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(expectedKotlinPaths.sorted(), manifestFiles)
        assertEquals(
            first.declarations.manifest.declarations.size,
            manifest.getValue("declarations").jsonArray.size,
        )

        val collisionReport = Json.parseToJsonElement(first.collisionReportJson).jsonObject
        assertEquals(1, collisionReport.getValue("formatVersion").jsonPrimitive.content.toInt())
        assertEquals(first.declarations.collisions.size, collisionReport.getValue("collisions").jsonArray.size)
        val coverageReport = Json.parseToJsonElement(first.coverageReportJson).jsonObject
        assertCoverageReportMatchesGeneration(first, coverageReport)
        assertEquals(
            (expectedKotlinPaths + DECLARATION_MANIFEST_PATH + COLLISION_REPORT_PATH + TL_CODEC_COVERAGE_REPORT_PATH).sorted(),
            first.allOutputBytes().keys.toList(),
        )

        val qualifiedDeclarations = first.files.flatMap { file ->
            file.declarations.map { declaration -> "${file.packageName}.$declaration" }
        }
        assertEquals(qualifiedDeclarations.size, qualifiedDeclarations.distinct().size)
        val manifestDeclarations = first.declarations.manifest.declarations.map {
            listOf(it.schemaKey, it.kind, it.tlName, it.constructorId)
        }
        assertEquals(manifestDeclarations.size, manifestDeclarations.distinct().size)

        assertEquals(guardedBefore, guardedWorkspaceSnapshot())
        assertEquals(buildBefore, generatedBuildOutputSnapshot())
        requireCampaignTime("acceptance 1 completion")
    }

    @Test
    fun `acceptance 2 generated sources preserve codec and registry boundaries`() {
        requireCampaignTime("acceptance 2 start")
        val result = generated
        val codecSource = result.codecs.files.joinToString("\n") { it.content }
        val registrySource = result.registries.files.joinToString("\n") { it.content }
        val allSource = result.files.joinToString("\n") { it.content }

        val message = transportCodecSource("message")
        val rpcResult = transportCodecSource("rpc_result")
        val gzipPacked = transportCodecSource("gzip_packed")
        assertEquals(1, message.countOccurrences("reader.readDeferredObject(_field2, context)"))
        assertEquals(1, message.countOccurrences("require(value.bytes == value.body.size)"))
        assertEquals(1, message.countOccurrences("writer.writeDeferredObject(value.body)"))
        assertEquals(1, rpcResult.countOccurrences("reader.readRemainingDeferredObject(context)"))
        assertEquals(1, rpcResult.countOccurrences("writer.writeDeferredObject(value.result)"))
        assertEquals(1, gzipPacked.countOccurrences("reader.readBytes(context)"))
        assertEquals(1, gzipPacked.countOccurrences("writer.writeBytes(value.packedData)"))
        assertEquals(1, codecSource.countOccurrences("readDeferredObject("))
        assertEquals(1, codecSource.countOccurrences("readRemainingDeferredObject("))
        assertEquals(2, codecSource.countOccurrences("writeDeferredObject("))
        assertFalse(gzipPacked.contains("readDeferredObject"))
        assertFalse(gzipPacked.contains("readRemainingDeferredObject"))

        val recursiveCalls = codecSource.lineSequence().filter { line ->
            line.contains(".decode(reader.readInt().toUInt(), reader, context") ||
                line.contains(".decodeMethod(reader.readInt().toUInt(), reader, context") ||
                line.contains(".readBare(reader, context.nested()") ||
                Regex("codec\\d+\\.read\\(reader, context").containsMatchIn(line)
        }.toList()
        assertTrue("No generated recursive codec calls found", recursiveCalls.isNotEmpty())
        recursiveCalls.forEach { line ->
            assertTrue("Recursive descent did not increment depth: $line", line.contains("context.nested()"))
        }
        assertFalse(codecSource.contains("context.copy("))
        assertFalse(codecSource.contains("TlDecodeContext("))

        result.registries.files.forEach { file ->
            assertBodyStartsWithSchemaCheck(
                file.content,
                "override fun decode(id: UInt, reader: TlReader, context: TlDecodeContext): TlObject {",
            )
            assertBodyStartsWithSchemaCheck(
                file.content,
                "fun decodeMethod(id: UInt, reader: TlReader, context: TlDecodeContext): TlMethod<*> {",
            )
            assertBodyStartsWithSchemaCheck(file.content, "): TlMethod<R> {")
        }
        assertEquals(14, result.registries.files.size)
        assertFalse(registrySource.contains("mapOf("))
        assertFalse(registrySource.contains("HashMap"))
        assertFalse(registrySource.contains("Map<"))

        val forbiddenGeneratedTokens = listOf(
            "java.lang.reflect",
            "kotlin.reflect",
            "Class.forName",
            "kotlinx.serialization",
            "Json.parse",
            "Json.decode",
            "java.nio.ByteBuffer",
            "java.net.Socket",
            "javax.crypto",
            "MessageDigest",
            "GZIPInputStream",
            "Inflater",
            "override fun readInt(",
            "override fun writeInt(",
            "class TlDecodeContext",
            "class TlLimits",
        )
        forbiddenGeneratedTokens.forEach { token ->
            assertFalse("Generated source contains forbidden runtime mechanism: $token", allSource.contains(token))
        }

        result.codecs.plan.exclusions.forEach { exclusion ->
            val registry = result.registries.plan.schemas.single { it.schemaKey == exclusion.schemaKey }
            val source = result.registries.files.single { it.relativePath == registry.relativePath }.content
            assertFalse(source.contains("${hex(exclusion.constructorId)} ->"))
        }
        assertGenerationSourcesHaveNoWorkspaceWrites()
        requireCampaignTime("acceptance 2 completion")
    }

    @Test
    fun `acceptance 3 every schema partition compiles against exactly six runtime ABI sources`() {
        requireCampaignTime("acceptance 3 start")
        val buildBefore = generatedBuildOutputSnapshot()
        val result = generated
        val runtimeSources = exactRuntimeSources()
        val stdlib = Path.of(Unit::class.java.protectionDomain.codeSource.location.toURI())
        val compileRoot = Files.createTempDirectory("wp004-s3-partitions")
        assertFalse(compileRoot.normalize().startsWith(repositoryRoot))
        val compiledPaths = mutableSetOf<String>()
        val started = System.nanoTime()

        result.declarations.symbolTable.schemas.forEachIndexed { index, symbols ->
            val schemaKey = symbols.schema.key
            val prefix = symbols.partition.packageName.replace('.', '/') + "/"
            val partitionFiles = result.files.filter { it.relativePath.startsWith(prefix) }
            val declarationFiles = result.declarations.files.filter { it.relativePath.startsWith(prefix) }
            val codecFiles = result.codecs.files.filter { it.relativePath.startsWith(prefix) }
            val registryFiles = result.registries.files.filter { it.relativePath.startsWith(prefix) }
            assertTrue("No declarations for $schemaKey", declarationFiles.isNotEmpty())
            assertTrue("No codecs for $schemaKey", codecFiles.isNotEmpty())
            assertEquals("Expected one registry for $schemaKey", 1, registryFiles.size)
            assertEquals(
                (declarationFiles + codecFiles + registryFiles).map { it.relativePath }.sorted(),
                partitionFiles.map { it.relativePath },
            )

            val sourceRoot = compileRoot.resolve("partition-$index-sources")
            partitionFiles.forEach { file ->
                val output = sourceRoot.resolve(file.relativePath)
                Files.createDirectories(output.parent)
                Files.writeString(output, file.content)
            }
            val generatedSources = kotlinSources(sourceRoot)
            assertEquals(partitionFiles.size, generatedSources.size)
            assertEquals(
                partitionFiles.map { it.relativePath }.sorted(),
                generatedSources.map { sourceRoot.relativize(it).toString().replace('\\', '/') }.sorted(),
            )

            val classes = compileRoot.resolve("partition-$index-classes")
            Files.createDirectories(classes)
            val elapsedBefore = elapsedSeconds(started)
            println(
                "S3 compiling $schemaKey at ${elapsedBefore}s: " +
                    "${declarationFiles.size} declarations, ${codecFiles.size} codecs, one registry",
            )
            System.out.flush()
            val diagnostics = compilePartition(
                compileRoot.resolve("partition-$index.args"),
                classes,
                stdlib,
                runtimeSources + generatedSources,
                "partition $schemaKey",
            )
            assertEquals("Partition $schemaKey\n${diagnostics.render()}", 0, diagnostics.exitCode)
            compiledPaths += partitionFiles.map(GeneratedKotlinFile::relativePath)
            requireCampaignTime("compiled partition $schemaKey")
            println("S3 compiled $schemaKey at ${elapsedSeconds(started)}s")
            System.out.flush()
        }

        assertEquals(result.files.map(GeneratedKotlinFile::relativePath).toSet(), compiledPaths)
        assertEquals(buildBefore, generatedBuildOutputSnapshot())
        requireCampaignTime("acceptance 3 completion")
        println("S3 compiled all 14 partitions in ${elapsedSeconds(started)}s")
    }

    private fun generateCorpus(): TlKotlinSourceGenerationResult {
        val schemas = TlSchemaDocumentReader.readManifest(repositoryRoot.resolve("protocol/schema/manifest.json"))
        val declarations = TlDeclarationGenerator().generate(schemas)
        return TlKotlinSourceGenerator().generate(declarations)
    }

    private fun transportCodecSource(tlName: String): String {
        val plan = generated.codecs.plan.declarationCodecs.single {
            it.schemaKey.kind == TlSchemaKind.TRANSPORT && it.tlName == tlName
        }
        return generated.codecs.files.single { it.relativePath == plan.relativePath }.content
    }

    private fun assertBodyStartsWithSchemaCheck(source: String, signature: String) {
        val start = source.indexOf(signature)
        assertTrue("Missing registry signature $signature", start >= 0)
        val body = source.substring(start + signature.length)
        assertTrue(
            "Registry does not validate schema before dispatch for $signature",
            body.trimStart().startsWith("if (schema != context.schema)"),
        )
    }

    private fun assertGenerationSourcesHaveNoWorkspaceWrites() {
        val emitRoot = repositoryRoot.resolve(
            "tools/tl-kotlin-codegen/src/main/kotlin/org/monogram/tools/tl/codegen/emit",
        )
        val source = kotlinSources(emitRoot).joinToString("\n") { Files.readString(it) }
        listOf(
            "Files.write",
            "Files.newOutputStream",
            "Files.createDirectories",
            "kotlin.io.path.write",
            ".toFile().write",
            "buildDirectory",
            "projectDir",
            "rootDir",
        ).forEach { token ->
            assertFalse("Generation source can mutate tracked/build output through $token", source.contains(token))
        }
    }

    private fun exactRuntimeSources(): List<Path> {
        val root = repositoryRoot.resolve("mtproto/src/main/java/org/monogram/mtproto/tl/runtime")
        val sources = kotlinSources(root)
        assertEquals(EXACT_RUNTIME_ABI_FILES, sources.map { it.fileName.toString() })
        return sources
    }

    private fun guardedWorkspaceSnapshot(): Map<String, String> = buildMap {
        GUARDED_SOURCE_ROOTS.forEach { relativeRoot ->
            val root = repositoryRoot.resolve(relativeRoot)
            Files.walk(root).use { paths ->
                paths.filter(Files::isRegularFile).sorted().forEach { path ->
                    put(repositoryRoot.relativize(path).toString().replace('\\', '/'), sha256(path))
                }
            }
        }
    }.toSortedMap()

    private fun generatedBuildOutputSnapshot(): Set<String> = BUILD_OUTPUT_ROOTS.flatMap { relativeRoot ->
        val root = repositoryRoot.resolve(relativeRoot)
        if (Files.notExists(root)) {
            emptyList()
        } else {
            Files.walk(root).use { paths ->
                paths.filter { path ->
                    Files.isRegularFile(path) &&
                        (path.fileName.toString().endsWith(".kt") ||
                            path.fileName.toString() == DECLARATION_MANIFEST_PATH ||
                            path.fileName.toString() == COLLISION_REPORT_PATH)
                }.map { repositoryRoot.relativize(it).toString().replace('\\', '/') }.sorted().toList()
            }
        }
    }.toSet()

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun compilePartition(
        argumentFile: Path,
        destination: Path,
        stdlib: Path,
        sources: List<Path>,
        campaignStage: String,
    ): BoundedProcessOutput {
        val arguments = buildList {
            add("-no-stdlib")
            add("-no-reflect")
            add("-jvm-target")
            add("17")
            add("-jdk-home")
            add(normalizedArgument(Path.of(System.getProperty("java.home"))))
            add("-classpath")
            add(normalizedArgument(stdlib))
            add("-d")
            add(normalizedArgument(destination))
            sources.forEach { add(normalizedArgument(it)) }
        }
        Files.writeString(argumentFile, arguments.joinToString("\n", postfix = "\n"))
        val javaExecutable = Path.of(
            System.getProperty("java.home"),
            "bin",
            if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java",
        )
        val process = ProcessBuilder(
            javaExecutable.toString(),
            "-Xmx3g",
            "-cp",
            System.getProperty("java.class.path"),
            "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
            "@${argumentFile.toAbsolutePath()}",
        ).redirectErrorStream(true).start()
        val output = BoundedProcessOutput()
        val drainThread = Thread({
            process.inputStream.bufferedReader().useLines { lines -> lines.forEach(output::append) }
        }, "k2-compiler-output-drain").apply {
            isDaemon = true
        }

        try {
            drainThread.start()
            val campaignRemainingNanos = remainingCampaignNanos(campaignStage)
            val partitionTimeoutNanos = TimeUnit.MINUTES.toNanos(K2_COMPILER_TIMEOUT_MINUTES)
            val waitNanos = minOf(partitionTimeoutNanos, campaignRemainingNanos)
            val timeoutDescription = if (campaignRemainingNanos <= partitionTimeoutNanos) {
                "$ACCEPTANCE_CAMPAIGN_TIMEOUT_MINUTES-minute acceptance campaign deadline"
            } else {
                "$K2_COMPILER_TIMEOUT_MINUTES-minute partition cap"
            }
            if (process.waitFor(waitNanos, TimeUnit.NANOSECONDS)) {
                output.exitCode = process.exitValue()
                val processHandles = snapshotProcessTree(process)
                val survivingHandles = processHandles.filter(ProcessHandle::isAlive)
                if (survivingHandles.isNotEmpty()) {
                    output.markOrphaned(survivingHandles.map(ProcessHandle::pid))
                    terminateProcessHandles(processHandles)
                }
            } else {
                output.markTimedOut(timeoutDescription)
                terminateProcess(process)
            }
        } catch (failure: Throwable) {
            terminateProcess(process)
            throw failure
        } finally {
            val processHandles = snapshotProcessTree(process)
            val survivingHandles = processHandles.filter(ProcessHandle::isAlive)
            if (survivingHandles.isNotEmpty()) {
                if (output.exitCode == 0) output.markOrphaned(survivingHandles.map(ProcessHandle::pid))
                terminateProcessHandles(processHandles)
            }
            process.inputStream.close()
            joinDrainThread(drainThread)
        }
        return output
    }

    private fun terminateProcess(process: Process) {
        terminateProcessHandles(snapshotProcessTree(process))
    }

    private fun snapshotProcessTree(process: Process): List<ProcessHandle> {
        val root = process.toHandle()
        val descendants = root.descendants().use { it.toList() }
        return (descendants.asReversed() + root).distinctBy(ProcessHandle::pid)
    }

    private fun terminateProcessHandles(handles: List<ProcessHandle>) {
        val fixedHandles = handles.toList()
        fixedHandles.filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroy)
        if (!awaitProcessHandlesExit(fixedHandles, PROCESS_DESTROY_WAIT_MILLIS)) {
            fixedHandles.filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly)
            check(awaitProcessHandlesExit(fixedHandles, PROCESS_FORCE_DESTROY_WAIT_MILLIS)) {
                val survivors = fixedHandles.filter(ProcessHandle::isAlive).joinToString { it.pid().toString() }
                "K2 compiler process handles survived forcible termination: $survivors"
            }
        }
    }

    private fun awaitProcessHandlesExit(handles: List<ProcessHandle>, timeoutMillis: Long): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        var interrupted = Thread.interrupted()
        while (handles.any(ProcessHandle::isAlive)) {
            val remainingNanos = deadline - System.nanoTime()
            if (remainingNanos <= 0) break
            val sleepMillis = TimeUnit.NANOSECONDS.toMillis(remainingNanos)
                .coerceIn(1, PROCESS_EXIT_POLL_MILLIS)
            try {
                Thread.sleep(sleepMillis)
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
        return handles.none(ProcessHandle::isAlive)
    }

    private fun joinDrainThread(thread: Thread) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DRAIN_THREAD_JOIN_WAIT_MILLIS)
        var interrupted = Thread.interrupted()
        while (thread.isAlive && System.nanoTime() < deadline) {
            val remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).coerceAtLeast(1)
            try {
                thread.join(remainingMillis)
            } catch (_: InterruptedException) {
                interrupted = true
                thread.interrupt()
            }
        }
        if (thread.isAlive) {
            thread.interrupt()
            try {
                thread.join(DRAIN_THREAD_FORCE_JOIN_WAIT_MILLIS)
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        check(!thread.isAlive) { "K2 compiler output drain thread survived bounded cleanup" }
        if (interrupted) Thread.currentThread().interrupt()
    }

    private fun normalizedArgument(path: Path): String =
        "\"${path.toAbsolutePath().normalize().toString().replace('\\', '/')}\""

    private fun assertByteMapsEqual(expected: Map<String, ByteArray>, actual: Map<String, ByteArray>) {
        assertEquals(expected.keys, actual.keys)
        expected.forEach { (path, bytes) ->
            assertTrue("Output differs between runs: $path", bytes.contentEquals(actual.getValue(path)))
        }
    }

    private fun assertCoverageReportMatchesGeneration(
        result: TlKotlinSourceGenerationResult,
        report: JsonObject,
    ) {
        assertJsonKeys("coverage report", report, "formatVersion", "exclusionDecision", "totals", "schemas")
        assertEquals(
            TL_CODEC_COVERAGE_REPORT_FORMAT_VERSION,
            report.getValue("formatVersion").jsonPrimitive.content.toInt(),
        )
        assertEquals("D-023", report.getValue("exclusionDecision").jsonPrimitive.content)

        val schemaReports = report.getValue("schemas").jsonArray
        val totals = report.getValue("totals").jsonObject
        assertJsonKeys(
            "coverage totals",
            totals,
            "schemas",
            "concreteConstructors",
            "methodResults",
            "d023Exclusions",
        )
        assertEquals(result.declarations.symbolTable.schemas.size, totals.uint("schemas").toInt())
        assertEquals(result.codecs.plan.schemas.sumOf { it.constructors.size }, totals.uint("concreteConstructors").toInt())
        assertEquals(result.codecs.plan.methodResultCodecs.size, totals.uint("methodResults").toInt())
        assertEquals(result.codecs.plan.exclusions.size, totals.uint("d023Exclusions").toInt())

        val declarationsByKey = result.declarations.symbolTable.schemas.associateBy { it.schema.key }
        val codecsByKey = result.codecs.plan.schemas.associateBy { it.schemaKey }
        val registriesByKey = result.registries.plan.schemas.associateBy { it.schemaKey }
        val actualKeys = schemaReports.map { schemaElement ->
            val identity = schemaElement.jsonObject.getValue("schemaIdentity").jsonObject
            assertJsonKeys("schema identity", identity, "kind", "layer")
            TlSchemaKey(
                kind = TlSchemaKind.valueOf(identity.string("kind").uppercase(Locale.ROOT)),
                layer = identity.getValue("layer").jsonPrimitive.contentOrNull?.toInt(),
            )
        }
        assertEquals(result.registries.plan.schemas.map { it.schemaKey }, actualKeys)
        assertEquals(declarationsByKey.keys.toSet(), actualKeys.toSet())
        assertEquals(actualKeys.size, actualKeys.distinct().size)

        schemaReports.zip(actualKeys).forEach { (schemaElement, schemaKey) ->
            val schemaReport = schemaElement.jsonObject
            assertJsonKeys(
                "schema report $schemaKey",
                schemaReport,
                "schemaIdentity",
                "snapshot",
                "registry",
                "counts",
                "concreteConstructors",
                "methodResults",
                "d023Exclusions",
            )
            val declarations = declarationsByKey.getValue(schemaKey)
            val codecs = codecsByKey.getValue(schemaKey)
            val registryPlan = registriesByKey.getValue(schemaKey)
            assertEquals(codecs.registry, registryPlan.contract)

            val provenance = declarations.schema.source.provenance
            val snapshot = schemaReport.getValue("snapshot").jsonObject
            assertJsonKeys(
                "schema snapshot $schemaKey",
                snapshot,
                "relativePath",
                "sourceTlPath",
                "sourceSha256",
                "exportedJsonSha256",
                "tellersCommit",
            )
            assertEquals(provenance?.artifactRelativePath, snapshot.nullableString("relativePath"))
            assertEquals(provenance?.sourceTlPath, snapshot.nullableString("sourceTlPath"))
            assertEquals(provenance?.sourceSha256, snapshot.nullableString("sourceSha256"))
            assertEquals(provenance?.exportedJsonSha256, snapshot.nullableString("exportedJsonSha256"))
            assertEquals(provenance?.tellersCommit, snapshot.nullableString("tellersCommit"))

            val registry = schemaReport.getValue("registry").jsonObject
            assertJsonKeys("schema registry $schemaKey", registry, "qualifiedName", "relativePath")
            assertEquals(registryPlan.contract.qualifiedName, registry.string("qualifiedName"))
            assertEquals(registryPlan.relativePath, registry.string("relativePath"))

            val expectedConstructors = codecs.constructors.map { plan ->
                assertEquals(plan.constructorId.toString(16).padStart(8, '0'), plan.constructorIdHex)
                CoverageEntryAssertion(
                    plan.tlName,
                    plan.constructorId,
                    hex(plan.constructorId),
                    plan.qualifiedCodecName,
                    plan.relativePath,
                )
            }.sortedWith(coverageEntryAssertionComparator)
            val methodsByName = codecs.methods.associateBy { it.tlName }
            val expectedMethodResults = codecs.methodResults.map { resultPlan ->
                val methodPlan = methodsByName.getValue(resultPlan.methodTlName)
                assertEquals(methodPlan.constructorId.toString(16).padStart(8, '0'), methodPlan.constructorIdHex)
                CoverageEntryAssertion(
                    resultPlan.methodTlName,
                    methodPlan.constructorId,
                    hex(methodPlan.constructorId),
                    resultPlan.qualifiedName,
                    methodPlan.relativePath,
                )
            }.sortedWith(coverageEntryAssertionComparator)
            val expectedExclusions = codecs.exclusions.sortedWith(
                compareBy(
                    { it.declarationKind.ordinal },
                    { it.constructorId.toLong() },
                    { it.tlName },
                    { it.reason.ordinal },
                ),
            ).map { exclusion ->
                CoverageExclusionAssertion(
                    exclusion.declarationKind.name.lowercase(Locale.ROOT),
                    exclusion.tlName,
                    exclusion.constructorId,
                    hex(exclusion.constructorId),
                    exclusion.reason.name.lowercase(Locale.ROOT),
                )
            }

            val actualConstructors = schemaReport.coverageEntries("concreteConstructors", schemaKey)
            val actualMethodResults = schemaReport.coverageEntries("methodResults", schemaKey)
            val actualExclusions = schemaReport.coverageExclusions(schemaKey)
            assertEquals("Concrete codec entries for $schemaKey", expectedConstructors, actualConstructors)
            assertEquals("Method-result entries for $schemaKey", expectedMethodResults, actualMethodResults)
            assertEquals("D-023 exclusions for $schemaKey", expectedExclusions, actualExclusions)

            val counts = schemaReport.getValue("counts").jsonObject
            assertJsonKeys(
                "schema counts $schemaKey",
                counts,
                "concreteConstructors",
                "methodResults",
                "d023Exclusions",
            )
            assertEquals(expectedConstructors.size, counts.uint("concreteConstructors").toInt())
            assertEquals(expectedMethodResults.size, counts.uint("methodResults").toInt())
            assertEquals(expectedExclusions.size, counts.uint("d023Exclusions").toInt())
        }
    }

    private fun JsonObject.coverageEntries(name: String, schemaKey: TlSchemaKey): List<CoverageEntryAssertion> =
        getValue(name).jsonArray.map { element ->
            val entry = element.jsonObject
            assertJsonKeys(
                "$name entry for $schemaKey",
                entry,
                "tlName",
                "constructorId",
                "constructorIdHex",
                "qualifiedCodecName",
                "codecRelativePath",
            )
            CoverageEntryAssertion(
                entry.string("tlName"),
                entry.uint("constructorId"),
                entry.string("constructorIdHex"),
                entry.string("qualifiedCodecName"),
                entry.string("codecRelativePath"),
            )
        }

    private fun JsonObject.coverageExclusions(schemaKey: TlSchemaKey): List<CoverageExclusionAssertion> =
        getValue("d023Exclusions").jsonArray.map { element ->
            val exclusion = element.jsonObject
            assertJsonKeys(
                "D-023 exclusion for $schemaKey",
                exclusion,
                "declarationKind",
                "tlName",
                "constructorId",
                "constructorIdHex",
                "reason",
            )
            CoverageExclusionAssertion(
                exclusion.string("declarationKind"),
                exclusion.string("tlName"),
                exclusion.uint("constructorId"),
                exclusion.string("constructorIdHex"),
                exclusion.string("reason"),
            )
        }

    private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content

    private fun JsonObject.nullableString(name: String): String? = getValue(name).jsonPrimitive.contentOrNull

    private fun JsonObject.uint(name: String): UInt = getValue(name).jsonPrimitive.content.toUInt()

    private fun assertJsonKeys(label: String, actual: JsonObject, vararg expected: String) {
        assertEquals("$label keys", expected.toSet(), actual.keys)
    }

    private fun requireCampaignTime(stage: String) {
        remainingCampaignNanos(stage)
    }

    private fun remainingCampaignNanos(stage: String): Long {
        val remaining = campaignDeadlineNanos - System.nanoTime()
        check(remaining > 0) {
            "Acceptance campaign exceeded $ACCEPTANCE_CAMPAIGN_TIMEOUT_MINUTES minutes at $stage"
        }
        return remaining
    }

    private fun kotlinSources(root: Path): List<Path> = Files.walk(root).use { paths ->
        paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".kt") }
            .sorted()
            .toList()
    }

    private fun String.countOccurrences(value: String): Int {
        var count = 0
        var index = indexOf(value)
        while (index >= 0) {
            count += 1
            index = indexOf(value, index + value.length)
        }
        return count
    }

    private fun hex(value: UInt): String = "0x${value.toString(16).padStart(8, '0')}u"

    private fun elapsedSeconds(started: Long): String =
        "%.3f".format(Locale.ROOT, (System.nanoTime() - started) / 1_000_000_000.0)

    private data class CoverageEntryAssertion(
        val tlName: String,
        val constructorId: UInt,
        val constructorIdHex: String,
        val qualifiedCodecName: String,
        val codecRelativePath: String,
    )

    private data class CoverageExclusionAssertion(
        val declarationKind: String,
        val tlName: String,
        val constructorId: UInt,
        val constructorIdHex: String,
        val reason: String,
    )

    private class BoundedProcessOutput {
        private val output = StringBuilder()
        private var lineCount = 0
        private var omittedCharacters = 0
        private var timeoutDescription: String? = null
        private var orphanedProcessIds: List<Long> = emptyList()
        var exitCode: Int = Int.MIN_VALUE

        fun markTimedOut(description: String) {
            timeoutDescription = description
            exitCode = K2_COMPILER_TIMEOUT_EXIT_CODE
        }

        fun markOrphaned(processIds: List<Long>) {
            orphanedProcessIds = processIds.sorted()
            exitCode = K2_COMPILER_ORPHAN_EXIT_CODE
        }

        fun append(line: String) {
            lineCount += 1
            val rendered = "$line\n"
            val remaining = MAX_DIAGNOSTIC_CHARACTERS - output.length
            if (remaining > 0) output.append(rendered.take(remaining))
            omittedCharacters += (rendered.length - remaining.coerceAtLeast(0)).coerceAtLeast(0)
        }

        fun render(): String = buildString {
            timeoutDescription?.let { append("K2 compiler timed out at ").append(it).appendLine('.') }
            if (orphanedProcessIds.isNotEmpty()) {
                append("K2 compiler root exited while tracked process handles remained alive: ")
                    .appendLine(orphanedProcessIds.joinToString())
            }
            append("Compiler diagnostics: ").append(lineCount).appendLine()
            append(output)
            if (omittedCharacters > 0) {
                appendLine()
                append("Diagnostics bounded at ").append(MAX_DIAGNOSTIC_CHARACTERS)
                    .append(" characters; omitted ").append(omittedCharacters).appendLine(" characters.")
            }
        }
    }

    companion object {
        private const val MAX_DIAGNOSTIC_CHARACTERS = 128 * 1024
        private const val ACCEPTANCE_CAMPAIGN_TIMEOUT_MINUTES = 35L
        private const val K2_COMPILER_TIMEOUT_MINUTES = 3L
        private const val K2_COMPILER_TIMEOUT_EXIT_CODE = -124
        private const val K2_COMPILER_ORPHAN_EXIT_CODE = -125
        private const val PROCESS_DESTROY_WAIT_MILLIS = 2_000L
        private const val PROCESS_FORCE_DESTROY_WAIT_MILLIS = 2_000L
        private const val PROCESS_EXIT_POLL_MILLIS = 25L
        private const val DRAIN_THREAD_JOIN_WAIT_MILLIS = 2_000L
        private const val DRAIN_THREAD_FORCE_JOIN_WAIT_MILLIS = 1_000L

        private val campaignDeadlineNanos by lazy {
            System.nanoTime() + TimeUnit.MINUTES.toNanos(ACCEPTANCE_CAMPAIGN_TIMEOUT_MINUTES)
        }
        private val repositoryRoot: Path = generateSequence(Path.of("").toAbsolutePath().normalize()) { it.parent }
            .first { Files.isRegularFile(it.resolve("protocol/schema/manifest.json")) }
        private val EXACT_RUNTIME_ABI_FILES = listOf(
            "TlContracts.kt",
            "TlExceptions.kt",
            "TlIo.kt",
            "TlLimits.kt",
            "TlSchema.kt",
            "TlValues.kt",
        )
        private val EXPECTED_SCHEMA_KEYS = setOf(
            TlSchemaKey(TlSchemaKind.CLOUD, 223),
            TlSchemaKey(TlSchemaKind.TRANSPORT, null),
            TlSchemaKey(TlSchemaKind.SECRET, 8),
            TlSchemaKey(TlSchemaKind.SECRET, 17),
            TlSchemaKey(TlSchemaKind.SECRET, 20),
            TlSchemaKey(TlSchemaKind.SECRET, 23),
            TlSchemaKey(TlSchemaKind.SECRET, 45),
            TlSchemaKey(TlSchemaKind.SECRET, 46),
            TlSchemaKey(TlSchemaKind.SECRET, 66),
            TlSchemaKey(TlSchemaKind.SECRET, 73),
            TlSchemaKey(TlSchemaKind.SECRET, 101),
            TlSchemaKey(TlSchemaKind.SECRET, 143),
            TlSchemaKey(TlSchemaKind.SECRET, 144),
            TlSchemaKey(TlSchemaKind.SECRET, 216),
        )
        private val GUARDED_SOURCE_ROOTS = listOf(
            "protocol/schema",
            "tools/tl-kotlin-codegen/src/main/kotlin",
            "mtproto/src/main/java/org/monogram/mtproto/tl/runtime",
        )
        private val BUILD_OUTPUT_ROOTS = listOf(
            "tools/tl-kotlin-codegen/build/generated",
            "mtproto/build/generated",
        )
        private val coverageEntryAssertionComparator = compareBy<CoverageEntryAssertion>(
            { it.constructorId.toLong() },
            CoverageEntryAssertion::tlName,
            CoverageEntryAssertion::qualifiedCodecName,
        )
        private val generated by lazy { TlKotlinSourceGeneratorAcceptanceTest().generateCorpus() }
    }
}
