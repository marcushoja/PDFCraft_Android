package de.pdfcraft.android

import android.app.AlertDialog
import android.content.Context
import android.widget.EditText

/** The same explicit destination browser is used for individual and bulk moves. */
class FolderDialogs(private val context: Context, private val store: DocumentStore, private val changed: () -> Unit) {
    private fun failure(error: Exception) {
        AlertDialog.Builder(context).setMessage(error.message ?: "Der Ordner konnte nicht geändert werden.")
            .setPositiveButton(android.R.string.ok, null).show()
    }

    private fun name(title: String, initial: String = "", action: (String) -> Unit) {
        val input = EditText(context).apply { setSingleLine(); setText(initial); selectAll(); hint = context.getString(R.string.folder_name) }
        val dialog = AlertDialog.Builder(context).setTitle(title).setView(input)
            .setNegativeButton(android.R.string.cancel, null).setPositiveButton(android.R.string.ok, null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            try { action(input.text.toString()); dialog.dismiss() }
            catch (error: Exception) { input.error = error.message }
        } }
        dialog.show()
    }

    fun create(parent: String?, done: ((LibraryFolder) -> Unit)? = null) {
        name(context.getString(R.string.folder_create)) {
            val folder = store.createFolder(it, parent)
            changed(); done?.invoke(folder)
        }
    }

    fun options(folder: LibraryFolder) {
        val labels = arrayOf(R.string.folder_rename, R.string.folder_move, R.string.folder_dissolve, R.string.folder_delete)
        AlertDialog.Builder(context).setTitle(folder.name).setItems(labels.map { context.getString(it) }.toTypedArray()) { _, which ->
            when (which) {
                0 -> name(context.getString(R.string.folder_rename), folder.name) { store.editFolder(folder.id, it, folder.parentId); changed() }
                1 -> choose(folder.parentId, LibraryFolders.descendants(store.folders(), folder.id)) { target ->
                    try { store.editFolder(folder.id, folder.name, target); changed() } catch (error: Exception) { failure(error) }
                }
                else -> AlertDialog.Builder(context).setTitle(labels[which])
                    .setMessage(if (which == 2) R.string.folder_dissolve_warning else R.string.folder_delete_warning)
                    .setNegativeButton(android.R.string.cancel, null).setPositiveButton(labels[which]) { _, _ ->
                        try { store.deleteFolder(folder.id, which == 3); changed() } catch (error: Exception) { failure(error) }
                    }.show()
            }
        }.show()
    }

    fun moveDocuments(ids: Set<String>, initial: String?) {
        choose(initial) { target ->
            try { store.moveDocuments(ids, target); changed() } catch (error: Exception) { failure(error) }
        }
    }

    fun choose(initial: String? = null, excluded: Set<String> = emptySet(), done: (String?) -> Unit) {
        fun browse(id: String?) {
            val folders = store.folders()
            val current = folders.firstOrNull { it.id == id }
            val children = folders.filter { it.parentId == current?.id && it.id !in excluded }.sortedBy { it.name.lowercase() }
            val parentLabel = context.getString(R.string.folder_up)
            val labels = (if (current != null) listOf(parentLabel) else emptyList()) + children.map { "▸ ${it.name}" }
            val path = context.getString(R.string.ui_library) + LibraryFolders.path(folders, current?.id).joinToString("", transform = { " › ${it.name}" })
            AlertDialog.Builder(context).setTitle(path).setItems(labels.toTypedArray()) { _, index ->
                if (current != null && index == 0) browse(current.parentId)
                else browse(children[index - if (current != null) 1 else 0].id)
            }.setPositiveButton(R.string.folder_choose) { _, _ -> done(current?.id) }
                .setNeutralButton(R.string.folder_create) { _, _ ->
                    create(current?.id) { browse(it.id) }
                }.setNegativeButton(android.R.string.cancel, null).show()
        }
        browse(initial.takeUnless { it in excluded })
    }
}
