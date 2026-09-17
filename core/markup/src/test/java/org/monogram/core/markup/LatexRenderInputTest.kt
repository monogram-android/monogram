package org.monogram.core.markup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LatexRenderInputTest {
    @Test
    fun permitsNestedFractionsRootsAndGreek() {
        assertTrue(isRenderableLatex("\\frac{\\sqrt{x^{2}+1}}{\\alpha_2}"))
    }

    @Test
    fun rejectsResourceLoadingMacroDefinitionsAndDimensions() {
        listOf("\\includegraphics{file}", "\\newcommand{\\x}{x}", "\\rule{999999pt}{2pt}", "\\unknown{x}")
            .forEach { assertFalse(it, isRenderableLatex(it)) }
    }

    @Test
    fun rejectsUnbalancedAndExcessiveInput() {
        listOf("{x", "x}", "x\\", "x".repeat(513), "{".repeat(17) + "x" + "}".repeat(17))
            .forEach { assertFalse(isRenderableLatex(it)) }
    }

    @Test
    fun escapedBracesDoNotChangeDepth() {
        assertTrue(isRenderableLatex("\\left\\{x\\right\\}"))
    }
}
