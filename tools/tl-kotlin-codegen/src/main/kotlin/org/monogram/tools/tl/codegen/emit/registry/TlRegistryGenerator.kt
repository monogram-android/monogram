package org.monogram.tools.tl.codegen.emit.registry

import org.monogram.tools.tl.codegen.emit.codec.TlCodecGenerationResult

class TlRegistryGenerator(
    private val planner: TlRegistryPlanner = TlRegistryPlanner(),
    private val emitter: TlRegistryEmitter = TlRegistryEmitter(),
) {
    fun generate(codecs: TlCodecGenerationResult): TlRegistryGenerationResult {
        val plan = planner.plan(codecs)
        val files = emitter.emit(plan)
        require(files == files.sortedBy { it.relativePath }) { "Generated registry files must be sorted" }
        return TlRegistryGenerationResult(files = files, plan = plan)
    }
}
