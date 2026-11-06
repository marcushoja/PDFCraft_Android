package de.pdfcraft.android

import android.app.AlertDialog
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** SAF access, explicit destructive confirmation and lifecycle-scoped worker ownership. */
class LibraryBackupUi(private val activity: ComponentActivity, private val store: () -> DocumentStore,
                      private val changed: (String?) -> Unit) : Closeable {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var closed = false
    private var busy = false
    private var cancelled = AtomicBoolean(false)
    private var pendingPassword: CharArray? = null
    private var prepared: LibraryBackup.Prepared? = null
    private var progressDialog: AlertDialog? = null
    private var dialog: AlertDialog? = null
    private val prefs get() = activity.getSharedPreferences("backup_status", 0)
    private val engine get() = LibraryBackup(activity.applicationContext, store())
    private val create = activity.activityResultRegistry.register("library-backup-create-v1", activity,
        ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val password = pendingPassword; pendingPassword = null
        if (password == null) {
            if (uri != null) message(activity.getString(R.string.backup_retry))
            return@register
        }
        if (uri == null) { password.fill('\u0000'); showPrepared(); return@register }
        export(uri, password)
    }
    private val open = activity.activityResultRegistry.register("library-backup-open-v1", activity, ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) password(false) { password -> prepare(uri, password) }
    }

    fun cleanAbandonedStages() {
        val context = activity.applicationContext // Only called after the Activity has been attached.
        worker.execute { runCatching { LibraryBackup.cleanAbandonedStages(context) } }
    }

    private fun message(text: String) { Toast.makeText(activity, text, Toast.LENGTH_LONG).show() }
    private fun display(builder: AlertDialog.Builder) {
        dialog?.dismiss()
        dialog = builder.create().also { it.show() }
    }

    fun show() {
        if (busy || closed) return
        val last = prefs.getLong("lastSuccess", 0)
        val status = if (last == 0L) activity.getString(R.string.backup_no_previous) else
            activity.getString(R.string.backup_last, java.text.DateFormat.getDateTimeInstance().format(java.util.Date(last)))
        display(AlertDialog.Builder(activity).setTitle(R.string.backup_title)
            .setMessage(activity.getString(R.string.backup_description) + "\n\n" + status)
            .setPositiveButton(R.string.backup_create) { _, _ -> startExport() }
            .setNeutralButton(R.string.backup_restore) { _, _ ->
                try { open.launch(arrayOf("*/*")) } catch (error: Exception) { message(error.message.orEmpty()) }
            }.setNegativeButton(android.R.string.cancel, null))
    }

    private fun password(creating: Boolean, done: (CharArray) -> Unit) {
        val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 16, 32, 16) }
        body.addView(TextView(activity).apply { setText(if (creating) R.string.backup_password_hint else R.string.backup_password_restore_hint) })
        fun field(label: Int) = EditText(activity).apply {
            hint = activity.getString(label); contentDescription = hint
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(); importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
            filters = arrayOf(android.text.InputFilter.LengthFilter(1024))
            body.addView(this)
        }
        val first = field(R.string.backup_password)
        val second = if (creating) field(R.string.backup_password_repeat) else null
        val prompt = AlertDialog.Builder(activity).setTitle(if (creating) R.string.backup_create else R.string.backup_restore)
            .setView(android.widget.ScrollView(activity).apply { addView(body) }).setNegativeButton(android.R.string.cancel) { _, _ -> showPrepared() }
            .setPositiveButton(android.R.string.ok, null).create()
        dialog = prompt
        prompt.setOnDismissListener { first.text.clear(); second?.text?.clear() }
        prompt.setOnCancelListener { showPrepared() }
        prompt.setOnShowListener { prompt.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val value = CharArray(first.length()) { first.text[it] }
            val repeated = second?.let { CharArray(it.length()) { index -> it.text[index] } }
            val matches = repeated == null || value.contentEquals(repeated)
            repeated?.fill('\u0000')
            if (creating && value.isNotEmpty() && value.size < 8) {
                value.fill('\u0000'); first.error = activity.getString(R.string.backup_password_short)
            } else if (second != null && !matches) {
                value.fill('\u0000'); second.error = activity.getString(R.string.backup_password_mismatch)
            } else { prompt.dismiss(); done(value) }
        } }
        prompt.show()
    }

    private fun startExport() {
        password(true) { value ->
            pendingPassword?.fill('\u0000'); pendingPassword = value
            val date = java.text.SimpleDateFormat("yyyy-MM-dd-HHmmss", java.util.Locale.ROOT).format(java.util.Date())
            try { create.launch("PDFPauli-Backup-$date.pdfpauli") }
            catch (error: Exception) { pendingPassword?.fill('\u0000'); pendingPassword = null; message(error.message.orEmpty()) }
        }
    }

    private fun begin(title: Int): (Int, Int) -> Unit {
        busy = true; cancelled = AtomicBoolean(false)
        val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 16, 32, 16) }
        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true }
        val label = TextView(activity).apply { setText(R.string.backup_preparing) }
        body.addView(bar); body.addView(label)
        val progress = AlertDialog.Builder(activity).setTitle(title).setView(body).setCancelable(false)
            .setNegativeButton(android.R.string.cancel, null).create()
        progressDialog = progress
        progress.setOnShowListener { progress.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            cancelled.set(true); progress.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = false
            label.setText(R.string.backup_cancelling)
        } }
        progress.show()
        return { count, total -> main.post {
            if (!closed && !cancelled.get()) {
                bar.isIndeterminate = total == 0
                bar.max = total.coerceAtLeast(1); bar.progress = count
                label.text = if (total > 0) activity.getString(R.string.backup_progress, count, total)
                    else activity.getString(R.string.backup_preparing)
            }
        }; Unit }
    }

    private fun finish(error: Throwable? = null) {
        busy = false; progressDialog?.dismiss(); progressDialog = null
        if (error != null && !cancelled.get()) message(error.message ?: activity.getString(R.string.backup_failed))
    }

    private fun export(uri: Uri, password: CharArray) {
        val progress = begin(R.string.backup_create)
        val token = cancelled
        worker.execute {
            val result = runCatching {
                activity.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                    engine.export(output, password, { token.get() }, progress)
                } ?: throw IOException(activity.getString(R.string.backup_failed))
                // The provider stream has successfully closed before reporting or recording success.
                prefs.edit().putLong("lastSuccess", System.currentTimeMillis()).commit()
            }
            password.fill('\u0000')
            if (result.isFailure) runCatching { android.provider.DocumentsContract.deleteDocument(activity.contentResolver, uri) }
            main.post { if (!closed) {
                finish(result.exceptionOrNull())
                if (result.isSuccess) message(activity.getString(R.string.backup_saved))
                showPrepared()
            } }
        }
    }

    private fun prepare(uri: Uri, password: CharArray) {
        val progress = begin(R.string.backup_checking)
        val token = cancelled
        worker.execute {
            val result = runCatching {
                activity.contentResolver.openInputStream(uri)?.use { engine.prepare(it, password, { token.get() }, progress) }
                    ?: throw IOException(activity.getString(R.string.backup_failed))
            }
            password.fill('\u0000')
            main.post {
                if (closed) { result.getOrNull()?.close(); return@post }
                finish(result.exceptionOrNull())
                result.onSuccess { prepared?.close(); prepared = it; showPrepared() }
            }
        }
    }

    private fun discard() { prepared?.close(); prepared = null }

    private fun showPrepared() {
        val backup = prepared ?: return
        if (closed) return
        val date = java.text.DateFormat.getDateTimeInstance().format(java.util.Date(backup.createdAt))
        display(AlertDialog.Builder(activity).setTitle(R.string.backup_restore)
            .setMessage(activity.getString(R.string.backup_summary, backup.documents.size, backup.folders.size, date))
            .setPositiveButton(R.string.backup_as_collection) { _, _ -> restore(false) }
            .setNeutralButton(R.string.backup_replace) { _, _ -> confirmReplacement() }
            .setNegativeButton(android.R.string.cancel) { _, _ -> discard() }.setOnCancelListener { discard() })
    }

    private fun confirmReplacement() {
        display(AlertDialog.Builder(activity).setTitle(R.string.backup_replace)
            .setMessage(R.string.backup_replace_warning)
            .setPositiveButton(R.string.backup_replace) { _, _ -> restore(true) }
            .setNeutralButton(R.string.backup_create) { _, _ -> startExport() }
            .setNegativeButton(android.R.string.cancel) { _, _ -> showPrepared() }
            .setOnCancelListener { showPrepared() })
    }

    private fun restore(replace: Boolean) {
        val backup = prepared ?: return
        prepared = null // The worker now owns cleanup, including when the Activity is destroyed.
        begin(R.string.backup_restore)
        val token = cancelled
        worker.execute {
            val result = runCatching { backup.use { engine.restore(it, replace) { token.get() } } }
            val settingsError = if (replace && result.isSuccess) runCatching { store().applyRestoredSettings() }.exceptionOrNull() else null
            main.post { if (!closed) {
                finish(result.exceptionOrNull())
                result.onSuccess { folder ->
                    message(activity.getString(if (settingsError == null) R.string.backup_restored else R.string.backup_settings_pending))
                    changed(folder)
                    if (replace) activity.recreate()
                }
            } }
        }
    }

    override fun close() {
        closed = true; cancelled.set(true); pendingPassword?.fill('\u0000'); pendingPassword = null
        discard(); dialog?.dismiss(); progressDialog?.dismiss()
        // Let the queued/running task observe cancellation and execute its cleanup finally blocks.
        worker.shutdown()
    }
}
