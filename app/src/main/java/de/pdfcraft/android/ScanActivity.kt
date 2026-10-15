package de.pdfcraft.android

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Private, resumable scanner draft; only the completed PDF leaves this activity. */
class ScanActivity : ComponentActivity() {
    private data class Page(val id: String, val raw: String, val image: String, val thumb: String,
        val corners: List<PointF>, val mode: ScanColorMode, val turns: Int,val tone:ScanTonePreset)
    private lateinit var session: File
    private val pages = mutableListOf<Page>()
    private var selected = 0
    private var pending: String? = null
    private var cropRaw: String? = null
    private var editId: String? = null
    private var cropCorners = ScanCropView.defaultCorners()
    private var cropMode = ScanColorMode.COLOR
    private var cropTone=ScanTonePreset.ORIGINAL
    private enum class AfterAccept { NONE,EXPORT }
    private var cropFromCamera=true
    private var cropTurnsOverride:Int?=null
    private var queuedImage:android.net.Uri?=null
    private lateinit var toneRail:LinearLayout
    private lateinit var toneScroll:ScrollView
    private var pendingContinuation=AfterAccept.NONE
    private var exportName = ""
    private lateinit var crop: ScanCropView
    private lateinit var empty: TextView
    private lateinit var actions: LinearLayout
    private lateinit var pageStrip:ScanPageStrip
    private lateinit var overlay: LinearLayout
    private lateinit var progressLabel: TextView
    private lateinit var more: ImageButton
    private val handler = Handler(Looper.getMainLooper())
    private val persistCorners = Runnable { if (!disposed) saveDraft() }
    private var generation = 0
    private var busy = false
    private var previewReload = false
    private var disposed = false
    private var cancellation = AtomicBoolean(false)
    private val colors by lazy { ZenColors.of(getSharedPreferences("reader_settings", MODE_PRIVATE).getBoolean("dark", false)) }
    private val camera = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val path = pending ?: return@registerForActivityResult
        pending = null
        val file = local(path)
        if (success && file.length() > 0) {
            cropRaw = path; editId = null; cropCorners = ScanCropView.defaultCorners();cropFromCamera=true;cropTurnsOverride=null
            saveDraft(); refresh(); loadPreview()
        } else { file.delete(); saveDraft(); refresh(); loadPreview() }
    }
    private val imagePicker=registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null) { queuedImage=uri;saveDraft();consumeSelectedImage() }
    }
    private fun consumeSelectedImage() {
        if(busy || disposed) return
        val uri=queuedImage ?: return
        queuedImage=null;saveDraft();importImage(uri)
    }
    private fun pickImage() {
        if(pages.size>=MAX_PAGES) { message(R.string.scan_limit);return }
        withCropConfirmation { runCatching {
            cropFromCamera=false;saveDraft();refresh()
            imagePicker.launch(arrayOf("image/*"))
        }.onFailure { message(R.string.scan_image_error) } }
    }
    private fun importImage(uri:android.net.Uri) {
        val file=local("${UUID.randomUUID()}.img")
        val cancel=AtomicBoolean(false)
        work(R.string.scan_busy,R.string.scan_image_error,{
            ScanImageImport.copy(this,uri,file) { cancel.get() };file
        },cancel,onAbandoned={ it.delete() }) {
            cropRaw=it.name;editId=null;cropCorners=ScanCropView.defaultCorners();cropFromCamera=false;cropTurnsOverride=null
            saveDraft();refresh();loadPreview()
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        val tag = LanguageContext.selected(newBase)
        if (tag != "system") applyOverrideConfiguration(Configuration(newBase.resources.configuration).apply {
            setLocales(android.os.LocaleList.forLanguageTags(tag))
        })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(applicationContext)
        val root = File(filesDir, "scanner-drafts").apply { mkdirs() }
        val draftPrefs = getSharedPreferences("scanner_draft", MODE_PRIVATE)
        val name = (savedInstanceState?.getString("scan_session") ?: draftPrefs.getString("session", null))
            ?.takeIf { it.matches(Regex("[a-f0-9-]{36}")) && File(root, it).isDirectory }
            ?: UUID.randomUUID().toString()
        session = File(root, name).apply { mkdirs() }
        // A durable pointer also resumes drafts on a fresh launcher invocation, not only recreation.
        draftPrefs.edit().putString("session", name).commit()
        exportName = "Scan-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(Date())}.pdf"
        val scanPrefs=getSharedPreferences("scanner_settings",MODE_PRIVATE)
        cropTone=runCatching { ScanTonePreset.valueOf(scanPrefs.getString("tone",null) ?: "ORIGINAL") }.getOrDefault(ScanTonePreset.ORIGINAL)
        cropMode=runCatching { ScanColorMode.valueOf(scanPrefs.getString("mode",null) ?: "COLOR") }.getOrDefault(ScanColorMode.COLOR)
        restoreDraft()
        saveDraft()
        createUi()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (busy) cancelWork() else if (cropRaw != null) cancelCrop() else confirmDiscard()
            }
        })
        refresh(); loadPreview();consumeSelectedImage()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun text(id: Int) = getString(id)
    private fun button(id: Int, action: () -> Unit) = Button(this).apply {
        setText(id); setTextColor(colors.accent); isAllCaps = false
        background=GradientDrawable().apply { setColor(this@ScanActivity.colors.tint);cornerRadius=dp(16).toFloat() }
        setPadding(dp(16),dp(10),dp(16),dp(10));minHeight=dp(48)
        layoutParams=LinearLayout.LayoutParams(-2,-2).apply { setMargins(dp(4),dp(4),dp(4),dp(4)) }
        setOnClickListener { if (!busy) action() }
    }
    private fun icon(key: String, id: Int, action: () -> Unit) = ImageButton(this).apply {
        contentDescription = text(id); setImageDrawable(MaterialSymbols.drawable(this@ScanActivity, key, colors.accent))
        setBackgroundColor(colors.tint); setOnClickListener { if (!busy) action() }
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
    }

    private fun createUi() {
        val frame = FrameLayout(this)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(colors.paper) }
        ViewCompat.setOnApplyWindowInsetsListener(frame) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            body.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        val toolbar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(colors.tint) }
        toolbar.addView(icon("back", R.string.scan_back) { onBackPressedDispatcher.onBackPressed() })
        toolbar.addView(TextView(this).apply { setText(R.string.scan_title); textSize = 20f; setTextColor(colors.ink);gravity=Gravity.CENTER_VERTICAL },
            LinearLayout.LayoutParams(0, dp(56), 1f))
        toolbar.addView(icon("camera", R.string.scan_take_photo) { takePhoto() }.apply { id = R.id.scan_take_photo_button })
        toolbar.addView(icon("image",R.string.scan_choose_image) { pickImage() }.apply { id=R.id.scan_choose_image_button })
        more = icon("more", R.string.scan_page_actions) { showMenu() }
        toolbar.addView(more)
        body.addView(toolbar)
        val preview = FrameLayout(this)
        crop = ScanCropView(this).apply {
            id = R.id.scan_crop
            accentColor = colors.accent
            onCornersChanged = {
                cropCorners = it
                handler.removeCallbacks(persistCorners)
                handler.postDelayed(persistCorners, 150)
            }
        }
        preview.addView(crop, FrameLayout.LayoutParams(-1, -1))
        empty = TextView(this).apply {
            id=R.id.scan_intro;text=ScanIntroText.text(this@ScanActivity,colors.accent)
            contentDescription=ScanIntroText.description(this@ScanActivity)
            setTextColor(colors.muted);gravity=Gravity.CENTER;setPadding(dp(24),0,dp(24),0)
        }
        preview.addView(empty, FrameLayout.LayoutParams(-1, -1))
        val previewRow=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        previewRow.addView(preview,LinearLayout.LayoutParams(0,-1,1f))
        toneRail=LinearLayout(this).apply { id=R.id.scan_tone_presets;orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER }
        toneScroll=ScrollView(this).apply { isFillViewport=true;addView(toneRail) }
        previewRow.addView(toneScroll,LinearLayout.LayoutParams(dp(80),-1))
        body.addView(previewRow, LinearLayout.LayoutParams(-1, 0, 1f))
        actions = LinearLayout(this).apply { gravity = Gravity.CENTER; orientation = LinearLayout.HORIZONTAL }
        body.addView(HorizontalScrollView(this).apply { isFillViewport = true; addView(actions) })
        pageStrip=ScanPageStrip(this,getSharedPreferences("reader_settings",MODE_PRIVATE).getBoolean("dark",false))
        body.addView(pageStrip,LinearLayout.LayoutParams(-1,dp(104)))
        frame.addView(body, FrameLayout.LayoutParams(-1, -1))
        overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setBackgroundColor(colors.paper)
            isClickable = true; isFocusable = true; visibility = View.GONE
            addView(ProgressBar(this@ScanActivity))
            progressLabel = TextView(this@ScanActivity).apply { setTextColor(colors.ink); gravity = Gravity.CENTER }
            addView(progressLabel)
            addView(Button(this@ScanActivity).apply { setText(R.string.scan_cancel); setOnClickListener { cancelWork() } })
        }
        frame.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        setContentView(frame)
    }

    private fun refresh() {
        selected = selected.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
        crop.editable = cropRaw != null
        more.isEnabled=cropRaw==null && pages.isNotEmpty()
        more.alpha=if(more.isEnabled) 1f else .35f
        crop.setAppearance(if(cropRaw!=null) cropMode else ScanColorMode.COLOR,if(cropRaw!=null) cropTone else ScanTonePreset.ORIGINAL)
        toneScroll.visibility=if(cropRaw!=null) View.VISIBLE else View.GONE
        toneRail.removeAllViews()
        val toneLabels=listOf(R.string.scan_tone_original,R.string.scan_tone_brighter,R.string.scan_tone_darker,R.string.scan_tone_contrast)
        ScanTonePreset.values().forEachIndexed { index,tone ->
            toneRail.addView(button(toneLabels[index]) {
                cropTone=tone;getSharedPreferences("scanner_settings",MODE_PRIVATE).edit().putString("tone",tone.name).apply();saveDraft();refresh()
            }.apply { textSize=11f;setPadding(dp(4),dp(8),dp(4),dp(8));isSelected=cropTone==tone
                background=GradientDrawable().apply { setColor(if(cropTone==tone) this@ScanActivity.colors.accent else this@ScanActivity.colors.tint);cornerRadius=dp(12).toFloat() }
                setTextColor(if(cropTone==tone) android.graphics.Color.WHITE else colors.accent)
            })
        }
        fun modeButton(label:Int,mode:ScanColorMode,viewId:Int) = button(label) {
            cropMode=if(cropMode==mode) ScanColorMode.COLOR else mode
            getSharedPreferences("scanner_settings",MODE_PRIVATE).edit().putString("mode",cropMode.name).apply();saveDraft();refresh()
        }.apply {
            id=viewId;textSize=11f;maxLines=2;setPadding(dp(4),dp(8),dp(4),dp(8));isSelected=cropMode==mode
            background=GradientDrawable().apply { setColor(if(cropMode==mode) this@ScanActivity.colors.accent else this@ScanActivity.colors.tint);cornerRadius=dp(12).toFloat() }
            setTextColor(if(cropMode==mode) android.graphics.Color.WHITE else colors.accent)
            androidx.core.view.ViewCompat.setStateDescription(this,getString(if(isSelected) R.string.ui_yes else R.string.ui_no))
        }
        toneRail.addView(modeButton(R.string.scan_grayscale,ScanColorMode.GRAYSCALE,R.id.scan_grayscale))
        toneRail.addView(modeButton(R.string.scan_black_white,ScanColorMode.DOCUMENT,R.id.scan_black_white))
        crop.contentDescription = if (cropRaw != null) text(R.string.scan_adjust_corners) else getString(R.string.scan_page, selected + 1)
        empty.visibility = if (cropRaw == null && pages.isEmpty()) View.VISIBLE else View.GONE
        actions.removeAllViews()
        if (cropRaw != null) {
            addPageActions(true)
        } else if(pages.isNotEmpty()) {
            addPageActions(false)
        }
        pageStrip.bind(pages.map { ScanStripPage(it.id,local(it.thumb)) },selected,!busy,
            onSelect={ index -> withCropConfirmation { selected=index;saveDraft();refresh();loadPreview() } },
            onDelete={ index -> confirmDeletePage(index) },onMove={ from,to -> withCropConfirmation { movePage(from,to) } })
    }
    private fun addPageActions(editing:Boolean) {
        fun action(key:String,label:Int,description:Int,viewId:Int=View.NO_ID,primary:Boolean=false,enabled:Boolean=true,run:()->Unit) {
            val cell=LinearLayout(this).apply {
                orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;setPadding(dp(2),dp(6),dp(2),dp(6))
                id=viewId;contentDescription=text(description);isFocusable=true
                setOnClickListener { if(enabled && !busy) run() };isEnabled=enabled;alpha=if(enabled) 1f else .35f
                addView(ImageView(this@ScanActivity).apply {
                    setPadding(dp(12),dp(12),dp(12),dp(12))
                    setImageDrawable(MaterialSymbols.drawable(this@ScanActivity,key,if(primary) android.graphics.Color.WHITE else colors.accent))
                    background=GradientDrawable().apply { setColor(if(primary) this@ScanActivity.colors.accent else this@ScanActivity.colors.tint);cornerRadius=dp(24).toFloat() }
                    importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },LinearLayout.LayoutParams(dp(48),dp(48)))
                addView(TextView(this@ScanActivity).apply { setText(label);textSize=11f;setTextColor(colors.ink);gravity=Gravity.CENTER;maxLines=2;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO })
            }
            actions.addView(cell,LinearLayout.LayoutParams(0,dp(82),1f))
        }
        action("rotate",R.string.scan_action_rotate,R.string.scan_rotate) { if(editing) rotateCrop() else rotateSelected() }
        action("crop",R.string.scan_action_adjust,R.string.scan_edit_page,R.id.scan_adjust_button,enabled=!editing) { editSelected() }
        action("delete",R.string.scan_action_delete,R.string.scan_delete_page) {
            if(editing && editId==null) AlertDialog.Builder(this).setTitle(R.string.scan_delete_page).setMessage(R.string.scan_delete_confirm)
                .setNegativeButton(R.string.scan_cancel,null).setPositiveButton(R.string.scan_delete_page) { _,_ -> cancelCrop() }.show()
            else confirmDeletePage(selected)
        }
        if(editing) action("check",R.string.scan_action_accept,R.string.scan_accept,R.id.scan_accept_button,true) { acceptCrop() }
        else action("continue",R.string.scan_action_continue,
            if(cropFromCamera) R.string.scan_continue_camera else R.string.scan_continue_image,
            R.id.scan_next_button,true,enabled=pages.size<MAX_PAGES) {
            if(cropFromCamera) takePhoto() else pickImage()
        }
        action("scan_export",R.string.scan_export_action,R.string.scan_export_action,R.id.scan_export_button) {
            if(editing) acceptAndContinue(AfterAccept.EXPORT) else export()
        }
    }
    private fun rotateSelected() {
        val p=pages.getOrNull(selected) ?: return
        processPage(p.raw,p.id,p.corners,p.mode,(p.turns+1)%4,p.tone)
    }
    private fun rotateCrop() {
        val raw=cropRaw ?: return
        val turns=((cropTurnsOverride ?: pages.find { it.id==editId }?.turns ?: 0)+1)%4
        val corners=cropCorners.map { PointF(it.x,it.y) }
        val output=local("${UUID.randomUUID()}.jpg")
        work(R.string.scan_busy,R.string.scan_error,{
            val source=ScanProcessing.loadPhoto(local(raw));var rotated:Bitmap?=null
            try {
                rotated=if(turns==0) source else Bitmap.createBitmap(source,0,0,source.width,source.height,Matrix().apply { postRotate(turns*90f) },true)
                output.outputStream().use { check(rotated.compress(Bitmap.CompressFormat.JPEG,95,it)) }
                var points=corners
                repeat(turns) { points=listOf(points[3],points[0],points[1],points[2]).map { PointF(1f-it.y,it.x) } }
                points
            } catch(error:Throwable) { output.delete();throw error }
            finally { if(rotated!==source) rotated?.recycle();source.recycle() }
        },onAbandoned={ output.delete() }) {
            cropRaw=output.name;cropCorners=it;cropTurnsOverride=0;saveDraft();refresh();loadPreview()
        }
    }

    private fun showMenu() {
        if (cropRaw != null || pages.isEmpty()) return
        val popup = PopupMenu(this, more)
        val ids = listOf(R.string.scan_edit_page, R.string.scan_rotate, R.string.scan_delete_page, R.string.scan_move_left, R.string.scan_move_right)
        ids.forEach { popup.menu.add(0, it, 0, it) }
        popup.menu.findItem(R.string.scan_move_left).isEnabled = selected > 0
        popup.menu.findItem(R.string.scan_move_right).isEnabled = selected < pages.lastIndex
        popup.setOnMenuItemClickListener {
            when (it.itemId) {
                R.string.scan_edit_page -> editSelected()
                R.string.scan_rotate -> rotateSelected()
                R.string.scan_delete_page -> confirmDeletePage(selected)
                R.string.scan_move_left -> move(-1)
                R.string.scan_move_right -> move(1)
            }; true
        }
        popup.show()
    }

    private fun move(delta: Int) {
        movePage(selected,selected+delta)
    }
    private fun movePage(from:Int,to:Int) {
        if(from !in pages.indices || to !in pages.indices || from==to) return
        val selectedId=pages.getOrNull(selected)?.id
        val page=pages.removeAt(from);pages.add(to,page)
        selected=pages.indexOfFirst { it.id==selectedId }.coerceAtLeast(0)
        saveDraft(); refresh()
    }
    private fun confirmDeletePage(index:Int) {
        if(busy) return
        val page=pages.getOrNull(index) ?: return
        AlertDialog.Builder(this).setTitle(getString(R.string.scan_delete_page_number,index+1)).setMessage(R.string.scan_delete_confirm)
            .setNegativeButton(R.string.scan_cancel,null).setPositiveButton(R.string.scan_delete_page) { _,_ ->
                withCropConfirmation {
                    val selectedId=pages.getOrNull(selected)?.id
                    pages.removeAll { it.id==page.id }
                    selected=pages.indexOfFirst { it.id==selectedId }.takeIf { it>=0 } ?: index.coerceAtMost((pages.size-1).coerceAtLeast(0))
                    saveDraft();refresh();loadPreview()
                }
            }.show()
    }

    private fun editSelected() {
        val page = pages.getOrNull(selected) ?: return
        cropRaw = page.raw; editId = page.id; cropCorners = page.corners.map { PointF(it.x, it.y) }; cropMode = page.mode;cropTone=page.tone
        cropTurnsOverride=null
        saveDraft(); refresh(); loadPreview()
    }

    private fun cancelCrop() {
        cropRaw = null; editId = null
        pendingContinuation=AfterAccept.NONE;cropTurnsOverride=null
        saveDraft(); refresh(); loadPreview()
    }

    private fun withCropConfirmation(action: () -> Unit) {
        if (cropRaw == null) { action(); return }
        AlertDialog.Builder(this).setTitle(R.string.scan_discard_edit_title).setMessage(R.string.scan_discard_edit_message)
            .setNegativeButton(R.string.scan_keep, null).setPositiveButton(R.string.scan_discard) { _, _ ->
                cropRaw = null; editId = null; saveDraft(); action()
            }.show()
    }

    private fun takePhoto() {
        if (pages.size >= MAX_PAGES) { message(R.string.scan_limit); return }
        withCropConfirmation {
            val file = local("${UUID.randomUUID()}.jpg")
            try {
                file.createNewFile(); pending = file.name; cropFromCamera=true; saveDraft(); refresh()
                camera.launch(FileProvider.getUriForFile(this, "$packageName.files", file))
            } catch (_: android.content.ActivityNotFoundException) {
                pending = null; file.delete(); saveDraft(); message(R.string.scan_camera_missing)
            } catch (_: Exception) {
                pending = null; file.delete(); saveDraft(); message(R.string.scan_camera_error)
            }
        }
    }

    private fun loadPreview() {
        if (busy) { previewReload = true; return }
        previewReload = false
        val raw = cropRaw
        val file = raw?.let(::local) ?: pages.getOrNull(selected)?.let { local(it.image) }
        if (file == null) { crop.setPhoto(null); return }
        val corners = cropCorners.map { PointF(it.x, it.y) }
        crop.setPhoto(null, corners)
        work(R.string.scan_busy, R.string.scan_error, { ScanProcessing.loadPhoto(file, 2200) }) {
            crop.setPhoto(it, if (raw != null) corners else ScanCropView.defaultCorners())
            if(raw==null) {
                val continuation=pendingContinuation;pendingContinuation=AfterAccept.NONE
                when(continuation) { AfterAccept.EXPORT -> export();AfterAccept.NONE -> Unit }
            }
        }
    }

    private fun acceptCrop() {
        acceptAndContinue(AfterAccept.NONE)
    }
    private fun acceptAndContinue(continuation:AfterAccept) {
        val raw = cropRaw ?: return
        if (editId == null && pages.size >= MAX_PAGES) { message(R.string.scan_limit); return }
        val turns = cropTurnsOverride ?: pages.find { it.id == editId }?.turns ?: 0
        processPage(raw, editId, cropCorners.map { PointF(it.x, it.y) }, cropMode, turns,cropTone,continuation)
    }

    private fun processPage(raw: String, existingId: String?, corners: List<PointF>, mode: ScanColorMode, turns: Int,tone:ScanTonePreset,continuation:AfterAccept=AfterAccept.NONE) {
        val id = existingId ?: UUID.randomUUID().toString()
        val revision = UUID.randomUUID().toString()
        val image = local("$revision-page.jpg")
        val thumb = local("$revision-thumb.jpg")
        work(R.string.scan_busy, R.string.scan_error, {
            val source = ScanProcessing.loadPhoto(local(raw), 2200)
            val owned = mutableListOf(source)
            try {
                val corrected = ScanProcessing.correctPerspective(source, corners.map { PointF(it.x * source.width, it.y * source.height) }, mode,tone).also { owned.add(it) }
                val rotated = if (turns == 0) corrected else Bitmap.createBitmap(corrected, 0, 0, corrected.width, corrected.height,
                    Matrix().apply { postRotate(turns * 90f) }, true)
                owned.add(rotated)
                image.outputStream().use { check(rotated.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
                val scale = 160f / maxOf(rotated.width, rotated.height)
                val tiny = Bitmap.createScaledBitmap(rotated, (rotated.width * scale).toInt().coerceAtLeast(1), (rotated.height * scale).toInt().coerceAtLeast(1), true).also { owned.add(it) }
                thumb.outputStream().use { check(tiny.compress(Bitmap.CompressFormat.JPEG, 85, it)) }
                Page(id, raw, image.name, thumb.name, corners.map { PointF(it.x, it.y) }, mode, turns,tone)
            } catch (e: Exception) { image.delete(); thumb.delete(); throw e }
            finally {
                // These worker-owned bitmaps have never been attached to a hardware canvas.
                owned.distinct().forEach { it.recycle() }
            }
        }) { page ->
            val index = pages.indexOfFirst { it.id == id }
            if (index >= 0) { pages[index] = page; selected = index } else { pages.add(page); selected = pages.lastIndex }
            cropRaw = null; editId = null;pendingContinuation=continuation; saveDraft(); refresh(); loadPreview()
        }
    }

    private fun export() {
        if (pages.isEmpty()) return
        val name = EditText(this).apply { setSingleLine(); setText(exportName);setSelectAllOnFocus(true);selectAll();hint = text(R.string.scan_name) }
        val dialog = AlertDialog.Builder(this).setTitle(R.string.scan_name).setView(name)
            .setNegativeButton(R.string.scan_cancel, null).setPositiveButton(R.string.scan_export, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val entered = name.text.toString().trim().replace(Regex("[\\\\/\\p{Cntrl}]"), "_").take(160)
                if (entered.isBlank()) { name.error = text(R.string.scan_name); return@setOnClickListener }
                exportName = if (entered.endsWith(".pdf", true)) entered else "$entered.pdf"
                saveDraft(); dialog.dismiss(); writeExport()
            }
        }
        dialog.show()
    }

    private fun writeExport() {
        val snapshots = pages.map { local(it.image) }
        val directory = File(cacheDir, "scanner-export").apply { mkdirs() }
        val output = File(directory, "${UUID.randomUUID()}.pdf")
        val cancel = AtomicBoolean(false)
        work(R.string.scan_exporting, R.string.scan_export_error, {
            try {
                ScanProcessing.writePdf(snapshots, output) { cancel.get() || Thread.currentThread().isInterrupted }
                if (cancel.get()) { output.delete(); throw java.util.concurrent.CancellationException() }
                FileProvider.getUriForFile(this, "$packageName.files", output, exportName)
            } catch (e: Exception) { output.delete(); throw e }
        }, cancel, onAbandoned = { output.delete() }) { uri ->
            setResult(RESULT_OK, Intent().setDataAndType(uri, "application/pdf")
                .putExtra(Intent.EXTRA_TITLE, exportName).putExtra(EXTRA_SCAN_DRAFT, session.name)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .apply { clipData = android.content.ClipData.newRawUri(exportName, uri) })
            disposed = true
            // Import is a separate operation and may fail/cancel. The parent acknowledges
            // success with clearImportedDraft; until then originals remain resumable.
            finish()
        }
    }

    private fun <T> work(label: Int, error: Int, task: () -> T, cancel: AtomicBoolean = AtomicBoolean(false), onAbandoned: (T) -> Unit = {}, done: (T) -> Unit) {
        if (busy || disposed) return
        busy = true; cancellation = cancel
        val ticket = ++generation
        progressLabel.setText(label); overlay.visibility = View.VISIBLE
        try { worker.execute {
            val result = runCatching { if (cancel.get()) throw java.util.concurrent.CancellationException(); task() }
            handler.post {
                if (disposed || isDestroyed || isFinishing || generation != ticket) {
                    result.getOrNull()?.let(onAbandoned)
                    return@post
                }
                busy = false; overlay.visibility = View.GONE
                if (cancel.get()) result.getOrNull()?.let(onAbandoned)
                else result.fold(done) { pendingContinuation=AfterAccept.NONE;message(error) }
                if (previewReload && !busy) loadPreview()
                if(!busy) consumeSelectedImage()
            }
        } } catch (_: java.util.concurrent.RejectedExecutionException) {
            busy = false; overlay.visibility = View.GONE; message(error)
        }
    }

    private fun cancelWork() {
        // Keep the overlay until the bounded worker returns; no overlapping writes or queued operations.
        cancellation.set(true)
        pendingContinuation=AfterAccept.NONE
        progressLabel.setText(R.string.scan_cancel)
    }

    private fun confirmDiscard() {
        AlertDialog.Builder(this).setTitle(R.string.scan_discard_title).setMessage(R.string.scan_discard_message)
            .setNegativeButton(R.string.scan_keep, null).setPositiveButton(R.string.scan_discard) { _, _ ->
                disposed = true; cancellation.set(true); generation++
                clearDraftPointer()
                val draft = session
                worker.execute { draft.deleteRecursively() }
                setResult(RESULT_CANCELED); finish()
            }.show()
    }

    private fun message(id: Int) { Toast.makeText(this, id, Toast.LENGTH_LONG).show() }
    private fun clearDraftPointer() {
        val prefs = getSharedPreferences("scanner_draft", MODE_PRIVATE)
        if (prefs.getString("session", null) == session.name) prefs.edit().remove("session").commit()
    }
    private fun local(name: String): File {
        require(name.isNotEmpty() && File(name).name == name && name != "." && name != "..")
        return File(session, name)
    }
    private fun encodeCorners(points: List<PointF>) = JSONArray().apply { points.forEach { put(JSONArray().put(it.x.toDouble()).put(it.y.toDouble())) } }
    private fun decodeCorners(array: JSONArray): List<PointF> = (0 until array.length()).map {
        PointF(array.getJSONArray(it).getDouble(0).toFloat(), array.getJSONArray(it).getDouble(1).toFloat())
    }.also { require(ScanCropView.validCorners(it)) }

    private fun saveDraft() {
        if (disposed) return
        val json = JSONObject().put("name", exportName).put("selected", selected).put("pending", pending)
            .put("crop", cropRaw).put("edit", editId).put("corners", encodeCorners(cropCorners)).put("mode", cropMode.name).put("tone",cropTone.name)
            .put("from_camera",cropFromCamera).put("crop_turns",cropTurnsOverride)
            .put("queued_image",queuedImage?.toString())
            .put("pages", JSONArray().apply { pages.forEach { p -> put(JSONObject().put("id", p.id).put("raw", p.raw)
                .put("image", p.image).put("thumb", p.thumb).put("corners", encodeCorners(p.corners)).put("mode", p.mode.name).put("turns", p.turns).put("tone",p.tone.name)) } })
        val atomic = AtomicFile(local("session.json"))
        var out: java.io.FileOutputStream? = null
        try { out = atomic.startWrite(); out.write(json.toString().toByteArray(Charsets.UTF_8)); atomic.finishWrite(out) }
        catch (_: Exception) { out?.let { atomic.failWrite(it) }; message(R.string.scan_error) }
    }

    private fun restoreDraft() {
        try {
            val json = JSONObject(AtomicFile(local("session.json")).openRead().use { it.readBytes().toString(Charsets.UTF_8) })
            val array = json.getJSONArray("pages")
            for (i in 0 until minOf(array.length(), MAX_PAGES)) {
                val p = array.getJSONObject(i)
                val page = Page(p.getString("id"), p.getString("raw"), p.getString("image"), p.getString("thumb"),
                    decodeCorners(p.getJSONArray("corners")), ScanColorMode.valueOf(p.getString("mode")), p.optInt("turns").coerceIn(0, 3),ScanTonePreset.valueOf(p.optString("tone","ORIGINAL")))
                if (local(page.raw).isFile && local(page.image).isFile && local(page.thumb).isFile) pages.add(page)
            }
            exportName = json.optString("name", exportName); selected = json.optInt("selected")
            pending = json.optString("pending").takeIf { it.isNotEmpty() && local(it).isFile }
            cropRaw = json.optString("crop").takeIf { it.isNotEmpty() && local(it).isFile }
            editId = json.optString("edit").takeIf { id -> pages.any { it.id == id } }
            cropCorners = decodeCorners(json.getJSONArray("corners")); cropMode = ScanColorMode.valueOf(json.getString("mode"))
            cropTone=ScanTonePreset.valueOf(json.optString("tone","ORIGINAL"))
            cropFromCamera=json.optBoolean("from_camera",true);cropTurnsOverride=if(json.has("crop_turns")) json.getInt("crop_turns").coerceIn(0,3) else null
            queuedImage=json.optString("queued_image").takeIf { it.isNotBlank() }?.let(android.net.Uri::parse)
        } catch (_: Exception) { /* A new draft or a interrupted state write; AtomicFile retains its last complete version. */ }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("scan_session", session.name)
        saveDraft(); super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        // Normally the ActivityResult callback handles this first. A new launch after process
        // death may have no callback, so recover the durable camera output instead of losing it.
        val path = pending ?: return
        pending = null
        val file = local(path)
        if (file.length() > 0) {
            cropRaw = path; editId = null; cropCorners = ScanCropView.defaultCorners();cropFromCamera=true;cropTurnsOverride=null
        } else file.delete()
        saveDraft(); refresh(); loadPreview()
    }

    override fun onDestroy() {
        disposed = true; generation++; cancellation.set(true)
        handler.removeCallbacks(persistCorners)
        // Do not recycle displayed bitmaps: a hardware render may still reference them.
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SCAN_DRAFT = "scan_draft"
        private const val MAX_PAGES = 100
        // Shared across configuration recreation; never more than one photo-sized operation at a time.
        private val worker = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(8))

        /** Call on the parent's IO worker only after the returned PDF was successfully imported. */
        fun clearImportedDraft(context: Context, token: String): Boolean {
            if (!token.matches(Regex("[a-f0-9-]{36}"))) return false
            return runCatching {
                val root = File(context.filesDir, "scanner-drafts").canonicalFile
                val draft = File(root, token)
                if (draft.canonicalFile.parentFile != root) return@runCatching false
                val deleted = !draft.exists() || draft.deleteRecursively()
                if (deleted) {
                    val prefs = context.getSharedPreferences("scanner_draft", Context.MODE_PRIVATE)
                    if (prefs.getString("session", null) == token) prefs.edit().remove("session").commit()
                }
                deleted
            }.getOrDefault(false)
        }
    }
}
