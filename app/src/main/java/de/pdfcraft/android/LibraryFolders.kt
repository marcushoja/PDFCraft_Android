package de.pdfcraft.android

import java.util.Locale
import java.util.UUID

data class LibraryFolder(val id: String, val name: String, val parentId: String? = null)

/** Logical folders only: moving never renames PDF files or changes document IDs. */
object LibraryFolders {
    fun newId(): String = UUID.randomUUID().toString()

    fun cleanName(name: String): String {
        val cleaned = name.trim()
        require(cleaned.isNotEmpty() && cleaned.length <= 128 &&
            cleaned.none { it.isISOControl() || it == '/' || it == '\\' } && cleaned !in listOf(".", "..")) {
            "Bitte einen gültigen Ordnernamen eingeben (maximal 128 Zeichen, ohne Schrägstriche)."
        }
        return cleaned
    }

    fun validate(folders: List<LibraryFolder>) {
        require(folders.size <= 10_000 && folders.map { it.id }.distinct().size == folders.size) { "Ungültige Ordnerstruktur." }
        val byId = folders.associateBy { it.id }
        val names = mutableSetOf<Pair<String?, String>>()
        for (folder in folders) {
            require(runCatching { UUID.fromString(folder.id).toString() == folder.id }.getOrDefault(false)) { "Ungültige Ordner-ID." }
            require(cleanName(folder.name) == folder.name) { "Ungültiger Ordnername." }
            require(names.add(folder.parentId to folder.name.lowercase(Locale.ROOT))) { "Dieser Ordnername existiert hier bereits." }
            val seen = mutableSetOf(folder.id)
            var parent = folder.parentId
            while (parent != null) {
                require(seen.add(parent) && seen.size <= 64) { "Ein Ordner darf nicht in sich selbst oder einen Unterordner verschoben werden (maximal 64 Ebenen)." }
                parent = (byId[parent] ?: throw IllegalArgumentException("Übergeordneter Ordner fehlt.")).parentId
            }
        }
    }

    fun descendants(folders: List<LibraryFolder>, id: String): Set<String> {
        val result = mutableSetOf(id)
        var changed: Boolean
        do { changed = false; folders.forEach { if (it.parentId in result && result.add(it.id)) changed = true } } while (changed)
        return result
    }

    fun path(folders: List<LibraryFolder>, id: String?): List<LibraryFolder> {
        val byId = folders.associateBy { it.id }
        val result = mutableListOf<LibraryFolder>()
        var current = id
        while (current != null && result.size < 64) {
            val folder = byId[current] ?: break
            result.add(folder); current = folder.parentId
        }
        return result.reversed()
    }
}
