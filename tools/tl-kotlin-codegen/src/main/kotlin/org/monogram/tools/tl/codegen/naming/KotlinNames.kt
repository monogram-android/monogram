package org.monogram.tools.tl.codegen.naming

import java.security.MessageDigest
import java.util.Locale

enum class KotlinNameStyle {
    TYPE,
    VALUE,
    PACKAGE,
}

data class KotlinNameRequest(
    val identity: String,
    val sourceName: String,
    val scope: String,
    val style: KotlinNameStyle,
    val role: String = "symbol",
)

data class KotlinNameAllocation(
    val request: KotlinNameRequest,
    val preferredName: String,
    val allocatedName: String,
) {
    val collided: Boolean get() = preferredName != allocatedName
}

data class KotlinNameCollision(
    val scope: String,
    val preferredName: String,
    val allocations: List<KotlinNameAllocation>,
)

data class KotlinNameAllocationResult(
    val allocations: Map<String, KotlinNameAllocation>,
    val collisions: List<KotlinNameCollision>,
) {
    operator fun get(identity: String): KotlinNameAllocation =
        allocations[identity] ?: error("No Kotlin name was allocated for $identity")
}

object KotlinNames {
    private val keywords = setOf(
        "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in",
        "interface", "is", "null", "object", "package", "return", "super", "this", "throw", "true",
        "try", "typealias", "typeof", "val", "var", "when", "while", "by", "catch", "constructor",
        "delegate", "dynamic", "field", "file", "finally", "get", "import", "init", "param", "property",
        "receiver", "set", "setparam", "where", "actual", "abstract", "annotation", "companion", "const",
        "crossinline", "data", "enum", "expect", "external", "final", "infix", "inline", "inner", "internal",
        "lateinit", "noinline", "open", "operator", "out", "override", "private", "protected", "public",
        "reified", "sealed", "suspend", "tailrec", "vararg", "value", "field", "it",
    )

    fun type(source: String): String = protect(toWords(source).joinToString("") { title(it) }, "Tl")

    fun value(source: String): String {
        val words = toWords(source)
        val value = words.firstOrNull()?.lowercase(Locale.ROOT).orEmpty() +
            words.drop(1).joinToString("") { title(it) }
        return protect(value, "value")
    }

    fun packageSegment(source: String): String {
        val normalized = source.lowercase(Locale.ROOT)
            .map { if (it.isLetterOrDigit() || it == '_') it else '_' }
            .joinToString("")
            .trim('_')
        return protect(normalized, "tl")
    }

    fun forStyle(source: String, style: KotlinNameStyle): String = when (style) {
        KotlinNameStyle.TYPE -> type(source)
        KotlinNameStyle.VALUE -> value(source)
        KotlinNameStyle.PACKAGE -> packageSegment(source)
    }

    internal fun stableSuffix(request: KotlinNameRequest, length: Int = INITIAL_SUFFIX_LENGTH): String {
        require(length in INITIAL_SUFFIX_LENGTH..MAX_SUFFIX_LENGTH && length % 2 == 0)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("${request.role}\u0000${request.sourceName}\u0000${request.identity}".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }.take(length)
    }

    private fun toWords(source: String): List<String> {
        val separated = source
            .replace(Regex("([A-Z]+)([A-Z][a-z])"), "$1_$2")
            .replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
            .replace(Regex("([A-Za-z])([0-9])"), "$1_$2")
            .replace(Regex("([0-9])([A-Za-z])"), "$1_$2")
        return separated.split(Regex("[^A-Za-z0-9]+"))
            .filter(String::isNotEmpty)
            .ifEmpty { listOf("tl") }
    }

    private fun title(word: String): String =
        word.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }

    private fun protect(candidate: String, fallback: String): String {
        var result = candidate.ifEmpty { fallback }
        if (result.first().isDigit()) result = "_$result"
        if (result in keywords) result += "_"
        return result
    }

    internal const val INITIAL_SUFFIX_LENGTH: Int = 10
    internal const val MAX_SUFFIX_LENGTH: Int = 64
}

class DeterministicKotlinNameAllocator {
    fun allocate(
        requests: Collection<KotlinNameRequest>,
        reservedNamesByScope: Map<String, Set<String>> = emptyMap(),
    ): KotlinNameAllocationResult {
        val duplicateIdentity = requests.groupingBy(KotlinNameRequest::identity).eachCount().entries.firstOrNull { it.value > 1 }
        require(duplicateIdentity == null) { "Duplicate Kotlin name request identity: ${duplicateIdentity?.key}" }

        val preferred = requests.associateWith { KotlinNames.forStyle(it.sourceName, it.style) }
        val occupiedByScope = requests.groupBy(KotlinNameRequest::scope).mapValuesTo(mutableMapOf()) { (scope, scoped) ->
            (scoped.mapTo(mutableSetOf()) { preferred.getValue(it) } + reservedNamesByScope[scope].orEmpty()).toMutableSet()
        }
        val allocations = linkedMapOf<String, KotlinNameAllocation>()
        val collisions = mutableListOf<KotlinNameCollision>()

        requests.groupBy { it.scope to preferred.getValue(it) }
            .toSortedMap(compareBy<Pair<String, String>>({ it.first }, { it.second }))
            .forEach { (scopeAndName, group) ->
                val (scope, base) = scopeAndName
                val sorted = group.sortedBy(KotlinNameRequest::identity)
                val mustDisambiguate = sorted.size > 1 || base in reservedNamesByScope[scope].orEmpty()
                val allocatedGroup = sorted.map { request ->
                    val allocated = if (mustDisambiguate) {
                        allocateSuffixed(request, base, occupiedByScope.getValue(scope))
                    } else {
                        base
                    }
                    KotlinNameAllocation(request, base, allocated).also { allocations[request.identity] = it }
                }
                if (mustDisambiguate) {
                    collisions += KotlinNameCollision(scope, base, allocatedGroup)
                }
            }

        return KotlinNameAllocationResult(
            allocations = allocations.toSortedMap(),
            collisions = collisions.sortedWith(compareBy(KotlinNameCollision::scope, KotlinNameCollision::preferredName)),
        )
    }

    private fun allocateSuffixed(
        request: KotlinNameRequest,
        base: String,
        occupied: MutableSet<String>,
    ): String {
        for (length in KotlinNames.INITIAL_SUFFIX_LENGTH..KotlinNames.MAX_SUFFIX_LENGTH step 2) {
            val candidate = "${base}_${KotlinNames.stableSuffix(request, length)}"
            if (occupied.add(candidate)) return candidate
        }
        throw TlGenerationException(
            reason = TlGenerationFailure.UNRESOLVABLE_NAME_COLLISION,
            schemaKey = null,
            declarationName = request.sourceName,
            expressionPath = request.scope,
            detail = "Every deterministic suffix for identity ${request.identity} is already occupied",
        )
    }
}
