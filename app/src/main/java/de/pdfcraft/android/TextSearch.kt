package de.pdfcraft.android

data class SearchHit(val page: Int, val snippet: String)
data class OutlineEntry(val title: String, val page: Int, val depth: Int)

/** Pure text helpers, independent of Android and PDFBox. */
object TextSearch {
    fun snippets(text: String, query: String, limit: Int = 200): List<String> {
        val needle = query.trim()
        if (needle.isEmpty() || limit <= 0) return emptyList()
        val compact = text.replace(Regex("\\s+"), " ").trim()
        val results = ArrayList<String>()
        var from = 0
        while (from < compact.length && results.size < limit) {
            val index = compact.indexOf(needle, from, ignoreCase = true)
            if (index < 0) break
            val start = (index - 55).coerceAtLeast(0)
            val end = (index + needle.length + 75).coerceAtMost(compact.length)
            results += (if (start > 0) "…" else "") + compact.substring(start, end).trim() +
                (if (end < compact.length) "…" else "")
            from = index + needle.length
        }
        return results
    }

    /** Conservatively unwrap prose; retain short lines, lists and apparent columns. */
    fun normalizeExtractedText(text: String): String {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n')
            .replace('\u00a0', ' ').split('\n')
        val result = StringBuilder()
        var previous = ""
        for (raw in lines) {
            val line = raw.trim().replace("\u00ad", "")
            if (line.isEmpty()) {
                if (result.isNotEmpty() && !result.endsWith("\n\n")) {
                    if (!result.endsWith("\n")) result.append('\n')
                    result.append('\n')
                }
                previous = ""
                continue
            }
            if (result.isNotEmpty() && !result.endsWith("\n")) {
                val columns = Regex("\\S[ \\t]{2,}\\S")
                val list = Regex("^(?:[-•*]|\\d+[.)])\\s")
                val prose = previous.length >= 60 && !columns.containsMatchIn(previous) &&
                    !columns.containsMatchIn(raw.trim()) && !list.containsMatchIn(line)
                val hyphen = prose && previous.endsWith('-') &&
                    previous.getOrNull(previous.length - 2)?.isLetter() == true &&
                    line.first().isLowerCase()
                when {
                    hyphen -> result.setLength(result.length - 1)
                    prose -> result.append(' ')
                    else -> result.append('\n')
                }
            }
            result.append(line)
            previous = raw.trim()
        }
        return result.toString().trim()
    }
}
