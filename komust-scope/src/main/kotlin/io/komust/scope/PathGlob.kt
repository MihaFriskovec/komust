package io.komust.scope

/**
 * A minimal, filesystem-independent glob for the `--files` override. Matches
 * against repo-root-relative, `/`-separated paths so behaviour does not depend
 * on the host OS path separator or `java.nio` `PathMatcher` quirks.
 *
 * Supported: `*` (any run of non-`/` characters), `**` (any run of characters,
 * `/` included), `?` (one non-`/` character). Everything else is literal. A
 * pattern with no `/` is additionally matched by basename anywhere in the tree,
 * so `Foo.kt` finds `src/main/kotlin/com/example/Foo.kt`.
 */
internal class PathGlob(private val pattern: String) {

    private val regexes: List<Regex> = buildList {
        add(compile(pattern))
        if ('/' !in pattern) add(compile("**/$pattern"))
    }

    fun matches(path: String): Boolean = regexes.any { it.matches(path) }

    private companion object {
        fun compile(glob: String): Regex {
            val sb = StringBuilder("^")
            var i = 0
            while (i < glob.length) {
                when (val c = glob[i]) {
                    '*' -> if (i + 1 < glob.length && glob[i + 1] == '*') {
                        sb.append(".*")
                        i++
                    } else {
                        sb.append("[^/]*")
                    }
                    '?' -> sb.append("[^/]")
                    '.', '(', ')', '+', '|', '^', '$', '@', '%', '{', '}', '[', ']', '\\' ->
                        sb.append('\\').append(c)
                    else -> sb.append(c)
                }
                i++
            }
            return Regex(sb.append('$').toString())
        }
    }
}
