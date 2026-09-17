package org.monogram.core.markup

/** Limits untrusted formula input before it reaches the rendering library. */
fun isRenderableLatex(source: String): Boolean {
    if (source.isBlank() || source.length > 512) return false
    var depth = 0
    var commands = 0
    var index = 0
    while (index < source.length) {
        when (source[index]) {
            '{' -> if (++depth > 16) return false
            '}' -> if (--depth < 0) return false
            '\\' -> {
                if (++commands > 128 || ++index == source.length) return false
                val start = index
                while (index < source.length && source[index].isLetter()) index++
                if (start == index) {
                    if (source[index] !in ",;:! {}|_%#&") return false
                    index++
                } else if (source.substring(start, index) !in allowedLatexCommands) {
                    return false
                }
                continue
            }
            '%', '#', '&', '$', '\u0000' -> return false
        }
        index++
    }
    return depth == 0
}

// Keep macro definitions, external resources and layout dimensions out of message rendering.
private val allowedLatexCommands = setOf(
    "frac", "dfrac", "tfrac", "sqrt", "binom", "overline", "underline", "vec", "hat", "bar", "dot", "ddot",
    "alpha", "beta", "gamma", "delta", "epsilon", "varepsilon", "zeta", "eta", "theta", "vartheta",
    "iota", "kappa", "lambda", "mu", "nu", "xi", "pi", "varpi", "rho", "varrho", "sigma", "varsigma",
    "tau", "upsilon", "phi", "varphi", "chi", "psi", "omega", "Gamma", "Delta", "Theta", "Lambda",
    "Xi", "Pi", "Sigma", "Upsilon", "Phi", "Psi", "Omega",
    "sum", "prod", "int", "iint", "iiint", "oint", "lim", "infty", "partial", "nabla",
    "sin", "cos", "tan", "cot", "sec", "csc", "arcsin", "arccos", "arctan", "sinh", "cosh", "tanh",
    "log", "ln", "exp", "min", "max", "sup", "inf", "det", "gcd",
    "times", "cdot", "div", "pm", "mp", "le", "leq", "ge", "geq", "ne", "neq", "approx", "equiv",
    "in", "notin", "subset", "subseteq", "supset", "supseteq", "cup", "cap", "emptyset", "forall", "exists",
    "neg", "land", "lor", "to", "rightarrow", "leftarrow", "leftrightarrow", "Rightarrow", "Leftarrow",
    "Leftrightarrow", "ldots", "cdots", "vdots", "ddots", "langle", "rangle", "lvert", "rvert",
    "left", "right", "text", "mathrm", "mathbf", "mathit", "mathsf", "mathtt", "mathbb", "mathcal",
    "quad", "qquad", "displaystyle", "textstyle",
)
