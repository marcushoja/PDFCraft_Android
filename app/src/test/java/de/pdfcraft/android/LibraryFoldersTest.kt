package de.pdfcraft.android

import org.junit.Assert.*
import org.junit.Test

class LibraryFoldersTest {
    private fun folder(name: String, parent: String? = null) = LibraryFolder(LibraryFolders.newId(), name, parent)

    @Test fun nestedFoldersHaveStablePathsAndSubtrees() {
        val root = folder("Work"); val child = folder("Invoices", root.id); val leaf = folder("2026", child.id)
        val other = folder("Private")
        val folders = listOf(root, child, leaf, other)
        LibraryFolders.validate(folders)
        assertEquals(listOf(root, child, leaf), LibraryFolders.path(folders, leaf.id))
        assertEquals(setOf(root.id, child.id, leaf.id), LibraryFolders.descendants(folders, root.id))
        assertEquals(emptyList<LibraryFolder>(), LibraryFolders.path(folders, null))
    }

    @Test fun namesAreTrimmedButUserTextIsNotTranslated() {
        assertEquals("Öffnen", LibraryFolders.cleanName(" Öffnen "))
        for (name in listOf("", "  ", ".", "..", "a/b", "a\\b", "a\nb", "x".repeat(129))) {
            assertThrows(IllegalArgumentException::class.java) { LibraryFolders.cleanName(name) }
        }
    }

    @Test fun rejectsDuplicateSiblingsButAllowsNamesInDifferentParents() {
        val a = folder("A"); val b = folder("B")
        LibraryFolders.validate(listOf(a, b, folder("Invoices", a.id), folder("Invoices", b.id)))
        assertThrows(IllegalArgumentException::class.java) { LibraryFolders.validate(listOf(a, folder("a"))) }
        assertThrows(IllegalArgumentException::class.java) { LibraryFolders.validate(listOf(a, a)) }
    }

    @Test fun rejectsCyclesMissingParentsInvalidIdsAndExcessiveDepth() {
        val a = folder("A"); val b = folder("B", a.id)
        assertThrows(IllegalArgumentException::class.java) { LibraryFolders.validate(listOf(a.copy(parentId = b.id), b)) }
        assertThrows(IllegalArgumentException::class.java) { LibraryFolders.validate(listOf(a.copy(parentId = a.id))) }
        assertThrows(IllegalArgumentException::class.java) { LibraryFolders.validate(listOf(b)) }
        assertThrows(IllegalArgumentException::class.java) { LibraryFolders.validate(listOf(a.copy(id = "../escape"))) }
        val folders = mutableListOf(a)
        repeat(64) { folders.add(folder("Level $it", folders.last().id)) }
        assertThrows(IllegalArgumentException::class.java) { LibraryFolders.validate(folders) }
    }
}
