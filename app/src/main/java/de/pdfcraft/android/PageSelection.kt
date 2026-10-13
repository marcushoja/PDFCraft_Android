package de.pdfcraft.android

object PageSelection {
    fun parse(input: String, pageCount: Int): List<Int> {
        require(input.isNotBlank()) { "Bitte Seiten angeben, z. B. 1-3, 5." }
        val result = linkedSetOf<Int>()
        for (part in input.split(',')) {
            val bounds = part.trim().split('-')
            require(bounds.size in 1..2 && bounds.all { it.trim().toIntOrNull() != null }) { "Ungültige Auswahl. Beispiel: 1-3, 5." }
            val first = bounds.first().trim().toInt()
            val last = bounds.last().trim().toInt()
            require(first in 1..pageCount && last in 1..pageCount) { "Seiten müssen zwischen 1 und $pageCount liegen." }
            val range = if (first <= last) first..last else first downTo last
            for (page in range) result.add(page - 1)
        }
        return result.toList()
    }
}
