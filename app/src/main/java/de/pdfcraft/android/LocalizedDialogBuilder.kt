package de.pdfcraft.android

import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface

/** Localizes app-owned labels. Use raw setters for document names and user-authored text. */
class LocalizedDialogBuilder(private val localizationContext: Context) : AlertDialog.Builder(localizationContext) {
    private fun translated(value: CharSequence?): CharSequence? = value?.let {
        val source = it.toString()
        UiText.t(localizationContext, source).let { result -> if (result == source) it else result }
    }

    override fun setTitle(title: CharSequence?): LocalizedDialogBuilder {
        super.setTitle(translated(title))
        return this
    }

    override fun setMessage(message: CharSequence?): LocalizedDialogBuilder {
        super.setMessage(translated(message))
        return this
    }

    override fun setPositiveButton(text: CharSequence?, listener: DialogInterface.OnClickListener?): LocalizedDialogBuilder {
        super.setPositiveButton(translated(text), listener)
        return this
    }

    override fun setNegativeButton(text: CharSequence?, listener: DialogInterface.OnClickListener?): LocalizedDialogBuilder {
        super.setNegativeButton(translated(text), listener)
        return this
    }

    override fun setNeutralButton(text: CharSequence?, listener: DialogInterface.OnClickListener?): LocalizedDialogBuilder {
        super.setNeutralButton(translated(text), listener)
        return this
    }

    override fun setItems(items: Array<out CharSequence>?, listener: DialogInterface.OnClickListener?): LocalizedDialogBuilder {
        super.setItems(items?.map { translated(it) ?: "" }?.toTypedArray(), listener)
        return this
    }

    override fun setMultiChoiceItems(items: Array<out CharSequence>?, checkedItems: BooleanArray?, listener: DialogInterface.OnMultiChoiceClickListener?): LocalizedDialogBuilder {
        super.setMultiChoiceItems(items?.map { translated(it) ?: "" }?.toTypedArray(), checkedItems, listener)
        return this
    }

    fun setRawTitle(title: CharSequence): LocalizedDialogBuilder {
        super.setTitle(title)
        return this
    }

    fun setRawMessage(message: CharSequence): LocalizedDialogBuilder {
        super.setMessage(message)
        return this
    }
}
