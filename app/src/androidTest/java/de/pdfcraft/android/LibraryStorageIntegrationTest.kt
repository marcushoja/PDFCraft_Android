package de.pdfcraft.android

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Paint
import android.graphics.pdf.PdfDocument as AndroidPdfDocument
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.EditText
import android.widget.GridView
import android.widget.ListView
import android.widget.TextView
import androidx.test.filters.SdkSuppress
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InterruptedIOException
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class LibraryStorageIntegrationTest {
    /** Isolates even destructive restore tests from the device's actual app library. */
    private class Sandbox(base: Context) : ContextWrapper(base), AutoCloseable {
        private val token = "storage-test-${UUID.randomUUID()}"
        private val root = File(base.cacheDir, token).apply { mkdirs() }
        private val preferenceNames = mutableSetOf<String>()
        var failNextLibraryCommit = false
        override fun getApplicationContext(): Context = this
        override fun getFilesDir() = File(root, "files").apply { mkdirs() }
        override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
        override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
            preferenceNames.add(name)
            val prefs = baseContext.getSharedPreferences("$token-$name", mode)
            return object : android.content.SharedPreferences by prefs {
                override fun edit(): android.content.SharedPreferences.Editor {
                    val editor = prefs.edit()
                    return object : android.content.SharedPreferences.Editor by editor {
                        override fun commit(): Boolean {
                            val result = editor.commit()
                            if (name == "pdfcraft_documents" && failNextLibraryCommit) {
                                failNextLibraryCommit = false
                                return false // Also exercises restoring SharedPreferences' in-memory state.
                            }
                            return result
                        }
                    }
                }
            }
        }
        override fun close() {
            preferenceNames.forEach { baseContext.deleteSharedPreferences("$token-$it") }
            root.deleteRecursively()
        }
    }

    private fun sample(context: Context): File {
        val source = File.createTempFile("backup-pdf-", ".pdf", context.cacheDir)
        val pdf = AndroidPdfDocument()
        try {
            repeat(3) { index ->
                val page = pdf.startPage(AndroidPdfDocument.PageInfo.Builder(240, 320, index + 1).create())
                page.canvas.drawText("${source.name} $index", 10f, 40f, Paint().apply { textSize = 12f })
                pdf.finishPage(page)
            }
            source.outputStream().use { pdf.writeTo(it) }
        } finally { pdf.close() }
        return source
    }

    private fun sandbox(action: (Sandbox) -> Unit) = Sandbox(InstrumentationRegistry.getInstrumentation().targetContext).use(action)

    @Test fun foldersAndBulkMovesKeepFilesProgressAndDeduplicationStable() = sandbox { context ->
        val store = DocumentStore(context)
        val firstSource = sample(context); val secondSource = sample(context)
        val first = store.importDocument(Uri.fromFile(firstSource)); val second = store.importDocument(Uri.fromFile(secondSource))
        val a = store.createFolder("Work"); val b = store.createFolder("Invoices", a.id)
        store.updateProgress(first.id, 1, .25f, 1.8f, 3, 90); store.toggleBookmark(first.id, 2)
        val before = store.get(first.id)!!; val bytes = store.file(first).readBytes()
        store.moveDocuments(setOf(first.id, second.id), b.id)
        assertEquals(before.copy(folderId = b.id), store.get(first.id))
        assertEquals(b.id, store.importDocument(Uri.fromFile(firstSource)).folderId)
        assertArrayEquals(bytes, store.file(first).readBytes())
        assertThrows(IllegalArgumentException::class.java) { store.editFolder(a.id, a.name, b.id) }
        assertThrows(IllegalArgumentException::class.java) { store.createFolder("work") }
        assertThrows(IllegalArgumentException::class.java) { store.moveDocuments(setOf(first.id, "missing"), null) }
        assertEquals(b.id, store.get(first.id)!!.folderId)
        store.deleteFolder(b.id, false)
        assertEquals(a.id, store.get(first.id)!!.folderId)
        store.deleteFolder(a.id, true)
        assertTrue(store.list().isEmpty()); assertFalse(store.file(first).exists())
        assertTrue(firstSource.exists()); assertTrue(secondSource.exists())
    }

    @Test fun fullBackupsRestoreNotesFoldersProgressAndSettingsWithoutOverwritingCollections() = sandbox { context ->
        val store = DocumentStore(context); val engine = LibraryBackup(context, store)
        val source = sample(context)
        val imported = store.importDocument(Uri.fromFile(source))
        val tools = PdfTools(context)
        val annotated = tools.addNote(store.file(imported), 1, "Important backup note")
        try { store.replaceContent(imported.id, annotated, 3, imported.contentHash) } finally { annotated.delete() }
        val parent = store.createFolder("Work"); val child = store.createFolder("Invoices", parent.id)
        store.moveDocuments(setOf(imported.id), child.id)
        store.updateProgress(imported.id, 2, .2f, 2f, 3, 270); store.toggleBookmark(imported.id, 1)
        store.updateTextProgress(imported.id, 2, .6f, 3)
        context.getSharedPreferences("reader_settings", 0).edit().putBoolean("dark", true).putString("language", "fr").putInt("font", 24).commit()
        context.getSharedPreferences("library_settings", 0).edit().putBoolean("list", true).putInt("sort", 3).commit()
        val original = store.get(imported.id)!!; val originalBytes = store.file(original).readBytes()
        val password = "test backup password".toCharArray()
        val output = ByteArrayOutputStream(); engine.export(output, password)
        val backup = output.toByteArray()
        store.rename(original.id, "Keep my newer name.pdf")
        context.getSharedPreferences("reader_settings", 0).edit().putBoolean("dark", false).commit()
        engine.prepare(ByteArrayInputStream(backup), password).use { prepared ->
            assertEquals(1, prepared.documents.size); assertEquals(2, prepared.folders.size)
            val collectionId = engine.restore(prepared, false)
            assertNotNull(collectionId)
            assertEquals(2, store.list().size)
            assertEquals("Keep my newer name.pdf", store.get(original.id)!!.name)
            assertFalse(context.getSharedPreferences("reader_settings", 0).getBoolean("dark", true))
            val restored = store.list().single { it.id != original.id }
            assertEquals(original.copy(id = restored.id, fileName = restored.fileName, folderId = restored.folderId), restored)
            assertEquals(listOf("${store.folders().first { it.id == collectionId }.name}", "Work", "Invoices"),
                LibraryFolders.path(store.folders(), restored.folderId).map { it.name })
            assertArrayEquals(originalBytes, store.file(restored).readBytes())
            assertEquals("Important backup note", tools.notes(store.file(restored)).single().text)
        }
        engine.prepare(ByteArrayInputStream(backup), password).use { engine.restore(it, true) }
        assertEquals(1, store.list().size); assertEquals(2, store.folders().size)
        // Simulate restart: the settings journal is replayable by a new store instance.
        DocumentStore(context).applyRestoredSettings()
        assertTrue(context.getSharedPreferences("reader_settings", 0).getBoolean("dark", false))
        assertEquals("fr", context.getSharedPreferences("reader_settings", 0).getString("language", null))
        assertEquals(3, context.getSharedPreferences("library_settings", 0).getInt("sort", 0))
        val restored = store.list().single()
        assertEquals(original.name, restored.name); assertEquals(original.page, restored.page)
        assertEquals(original.bookmarks, restored.bookmarks); assertEquals(original.viewRotation, restored.viewRotation)
        assertArrayEquals(originalBytes, store.file(restored).readBytes())
    }

    private fun rewriteArchive(backup: ByteArray, transform: (String, ByteArray) -> Pair<String, ByteArray>): ByteArray {
        val plain = ByteArrayOutputStream()
        BackupEnvelope.read(ByteArrayInputStream(backup), plain, charArrayOf())
        val changed = ByteArrayOutputStream()
        ZipInputStream(ByteArrayInputStream(plain.toByteArray())).use { zip ->
            ZipOutputStream(changed).use { out ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val (name, bytes) = transform(entry.name, zip.readBytes())
                    out.putNextEntry(ZipEntry(name)); out.write(bytes); out.closeEntry()
                }
            }
        }
        return ByteArrayOutputStream().also { BackupEnvelope.write(ByteArrayInputStream(changed.toByteArray()), it, charArrayOf()) }.toByteArray()
    }

    @Test fun malformedChecksumsPathsVersionsAndSettingsNeverChangeTheLibrary() = sandbox { context ->
        val store = DocumentStore(context); val engine = LibraryBackup(context, store)
        val source = sample(context); val doc = store.importDocument(Uri.fromFile(source))
        val output = ByteArrayOutputStream(); engine.export(output)
        val before = store.exportMetadata().toString(); val bytes = store.file(doc).readBytes()
        val mutations = listOf<(String, ByteArray) -> Pair<String, ByteArray>>(
            { name, data -> if (name.endsWith(".pdf")) name to data.copyOf().also { it[it.lastIndex] = 1 } else name to data },
            { name, data -> (if (name.endsWith(".pdf")) "../escape.pdf" else name) to data },
            { name, data -> name to if (name == "manifest.json") JSONObject(String(data)).put("version", 999).toString().toByteArray() else data },
            { name, data -> name to if (name == "manifest.json") JSONObject(String(data)).apply {
                getJSONObject("settings").getJSONObject("reader_settings").put("dark", "not a boolean")
            }.toString().toByteArray() else data },
        )
        mutations.forEach { mutation ->
            val bad = rewriteArchive(output.toByteArray(), mutation)
            assertThrows(Exception::class.java) { engine.prepare(ByteArrayInputStream(bad)).close() }
            assertEquals(before, store.exportMetadata().toString()); assertArrayEquals(bytes, store.file(doc).readBytes())
            assertFalse(context.cacheDir.listFiles().orEmpty().any { it.name.startsWith("library-backup-") })
        }
    }

    @Test fun cancellationDuringPreparationAndInstallationLeavesTheOldLibraryIntact() = sandbox { context ->
        val store = DocumentStore(context); val engine = LibraryBackup(context, store)
        repeat(2) { store.importDocument(Uri.fromFile(sample(context))) }
        val output = ByteArrayOutputStream(); engine.export(output)
        val before = store.exportMetadata().toString()
        assertThrows(InterruptedIOException::class.java) { engine.prepare(ByteArrayInputStream(output.toByteArray()), cancelled = { true }) }
        engine.prepare(ByteArrayInputStream(output.toByteArray())).use { prepared ->
            var calls = 0
            assertThrows(InterruptedIOException::class.java) { engine.restore(prepared, true) { ++calls >= 3 } }
        }
        assertEquals(before, store.exportMetadata().toString())
        assertTrue(store.list().all { store.file(it).isFile })
        assertEquals(2, store.directory.listFiles().orEmpty().count { it.extension == "pdf" })
        engine.prepare(ByteArrayInputStream(output.toByteArray())).use { prepared ->
            context.failNextLibraryCommit = true
            assertThrows(java.io.IOException::class.java) { engine.restore(prepared, true) }
        }
        assertEquals(before, store.exportMetadata().toString())
        assertTrue(store.list().all { store.file(it).isFile })
        assertEquals(2, store.directory.listFiles().orEmpty().count { it.extension == "pdf" })
    }

    @Test fun emptyLibraryBackupsAreValidAndCanReplaceAnExistingLibrary() = sandbox { context ->
        val store = DocumentStore(context); val engine = LibraryBackup(context, store)
        val output = ByteArrayOutputStream(); engine.export(output)
        store.importDocument(Uri.fromFile(sample(context)))
        engine.prepare(ByteArrayInputStream(output.toByteArray())).use { prepared ->
            assertTrue(prepared.documents.isEmpty()); assertTrue(prepared.folders.isEmpty())
            engine.restore(prepared, true)
        }
        assertTrue(store.list().isEmpty()); assertTrue(store.folders().isEmpty())
    }

    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    @Test @SdkSuppress(minSdkVersion = 29)
    fun libraryBrowsesFoldersBulkMovesAndSearchesAcrossTheEntireLibrary() = sandbox { context ->
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val store = DocumentStore(context)
        val documents = List(2) { store.importDocument(Uri.fromFile(sample(context))) }
        store.rename(documents[0].id, "Global needle.pdf")
        val folder = store.createFolder("Öffnen")
        val host = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val thumbnails = ThumbnailLoader(context)
        try {
            lateinit var view: LibraryView
            instrumentation.runOnMainSync {
                view = LibraryView(host, store, thumbnails, false, { fail("Selection must not open a PDF") }, {}, {}, {})
                host.setContentView(view)
                val grid = descendants(view).filterIsInstance<GridView>().single()
                assertEquals(2, grid.adapter.count)
                val first = grid.adapter.getView(0, null, grid)
                assertTrue(first.performLongClick())
                descendants(view).single { it.contentDescription == host.getString(R.string.folder_select_all) }.performClick()
                descendants(view).single { it.contentDescription == host.getString(R.string.folder_move) }.performClick()
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val list = WindowInspector.getGlobalWindowViews().flatMap { descendants(it) }.filterIsInstance<ListView>().single()
                assertEquals("▸ ${folder.name}", list.adapter.getItem(0).toString())
                list.performItemClick(null, 0, list.adapter.getItemId(0))
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                WindowInspector.getGlobalWindowViews().flatMap { descendants(it) }.single { it.id == android.R.id.button1 }.performClick()
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val grid = descendants(view).filterIsInstance<GridView>().single()
                assertEquals(0, grid.adapter.count)
                assertTrue(store.list().all { it.folderId == folder.id })
                // User-authored folder names stay opaque, including names that are also UI labels.
                descendants(view).single { it.contentDescription == folder.name }.performClick()
                assertEquals(2, grid.adapter.count)
                assertTrue(view.handleBack()); assertEquals(0, grid.adapter.count)
                descendants(view).filterIsInstance<EditText>().single().setText("needle")
                assertEquals(1, grid.adapter.count)
                val result = grid.adapter.getView(0, null, grid)
                assertTrue(descendants(result).filterIsInstance<TextView>().any { it.text.contains(folder.name) })
            }
        } finally {
            instrumentation.runOnMainSync { host.finish(); thumbnails.close() }
            instrumentation.waitForIdleSync()
        }
    }
}
