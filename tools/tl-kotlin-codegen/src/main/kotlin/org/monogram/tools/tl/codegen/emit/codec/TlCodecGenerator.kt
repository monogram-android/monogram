package org.monogram.tools.tl.codegen.emit.codec

import org.monogram.tools.tl.codegen.emit.declaration.TlDeclarationGenerationResult

class TlCodecGenerator(
    private val planner: TlCodecPlanner = TlCodecPlanner(),
    private val emitter: TlCodecEmitter = TlCodecEmitter(),
) {
    fun generate(declarations: TlDeclarationGenerationResult): TlCodecGenerationResult {
        val plan = planner.plan(declarations)
        val files = emitter.emit(plan)
        require(files == files.sortedBy { it.relativePath }) { "Generated codec files must be sorted" }
        return TlCodecGenerationResult(files = files, plan = plan)
    }
}
