package de.pdfcraft.android

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import java.util.Locale

/**
 * Compatibility bridge for existing German UI labels. The German resource catalog is the
 * source dictionary; actual output always comes from the caller's localized resources.
 * Only complete known labels/templates are recognized. Captured document content is opaque.
 * Do not call this on bare document titles, chapter text, annotations, or extracted PDF text.
 */
object UiText {
    private data class Template(val id: Int, val regex: Regex, val positions: List<Int>)
    private data class Catalog(val exact: Map<String, Int>, val templates: List<Template>)
    @Volatile private var catalog: Catalog? = null
    private val placeholder = Regex("%(\\d+)\\\$s")

    fun t(context: Context, source: String): String = translate(context, source, 0)

    private fun translate(context: Context, source: String, depth: Int): String {
        val dictionary = catalog ?: synchronized(this) {
            catalog ?: buildCatalog(context).also { catalog = it }
        }
        dictionary.exact[source]?.let { return context.getString(it) }
        for (template in dictionary.templates) {
            val match = template.regex.matchEntire(source) ?: continue
            val values = Array(template.positions.maxOrNull() ?: 0) { "" }
            template.positions.forEachIndexed { index, position -> values[position - 1] = match.groupValues[index + 1] }
            // These are app-generated fields, not PDF/user content. No other capture is translated.
            if (template.id == R.string.ui_properties) {
                values[4] = when (values[4]) {
                    "Ja" -> context.getString(R.string.ui_yes)
                    "Nein" -> context.getString(R.string.ui_no)
                    else -> values[4]
                }
            } else if (template.id == R.string.ui_export_message) {
                val warning = dictionary.exact[values[1]]
                if (warning == R.string.ui_protected_copy_warning || warning == R.string.ui_edited_copy_warning) {
                    values[1] = context.getString(warning)
                }
            }
            return context.getString(template.id, *values)
        }
        // Backend operations wrap IOException messages. Localize only recognized error prefixes
        // and recurse on the remainder, never on arbitrary colon-delimited document text.
        if (depth < 32) {
            val colon = source.indexOf(':')
            if (colon > 0) {
                val id = dictionary.exact[source.substring(0, colon)]
                if (id != null && id in errorPrefixes) {
                    val remainder = source.substring(colon + 1)
                    val leading = remainder.takeWhile { it == ' ' || it == '\t' }
                    return context.getString(id) + ":" + leading + translate(context, remainder.substring(leading.length), depth + 1)
                }
            }
        }
        return source
    }

    private fun buildCatalog(context: Context): Catalog {
        val config = Configuration(context.resources.configuration).apply {
            setLocales(LocaleList(Locale.GERMAN))
        }
        val german = context.createConfigurationContext(config)
        val exact = mutableMapOf<String, Int>()
        val templates = mutableListOf<Template>()
        for (id in ids) {
            val source = german.getString(id)
            val fields = placeholder.findAll(source).toList()
            if (fields.isEmpty()) {
                exact[source] = id
            } else {
                val expression = StringBuilder()
                var offset = 0
                for (field in fields) {
                    expression.append(Regex.escape(source.substring(offset, field.range.first)))
                    val position = field.groupValues[1].toInt()
                    val numeric = when (id) {
                        R.string.ui_properties -> position == 3 || position == 6
                        R.string.ui_search_hit, R.string.ui_note_item -> position == 1
                        else -> id in numericTemplates
                    }
                    expression.append(if (numeric) "([0-9]+(?:[.,][0-9]+)?|\\{[0-9]+\\})" else "(.*?)")
                    offset = field.range.last + 1
                }
                expression.append(Regex.escape(source.substring(offset)))
                templates += Template(id, Regex(expression.toString(), RegexOption.DOT_MATCHES_ALL), fields.map { it.groupValues[1].toInt() })
            }
        }
        return Catalog(exact, templates)
    }

    private val numericTemplates = setOf(
        R.string.ui_page_fraction, R.string.ui_page_range, R.string.ui_hits,
        R.string.ui_note_page, R.string.ui_enter_number, R.string.ui_note_title,
        R.string.ui_jump_number, R.string.ui_page_progress, R.string.ui_copy_progress,
        R.string.ui_copy_size, R.string.ui_pdf_page, R.string.ui_page_of,
        R.string.ui_reflow_page, R.string.ui_pages_bounds,
        R.string.ui_enter_page_number, R.string.ui_search_progress
    )

    private val errorPrefixes = setOf(
        R.string.ui_save_failed, R.string.ui_processing_failed, R.string.ui_import_failed,
        R.string.ui_password_check_failed, R.string.ui_unlock_failed, R.string.ui_merge_failed,
        R.string.ui_properties_failed, R.string.ui_forms_failed, R.string.ui_notes_failed,
        R.string.ui_protect_failed, R.string.ui_edit_failed, R.string.ui_pdf_import_failed,
        R.string.ui_print_failed, R.string.ui_form_load_failed, R.string.ui_text_read_failed
    )

    // Explicit references keep resource IDs stable, readable, and visible to resource shrinkers.
    private val ids = intArrayOf(
        R.string.ui_cancel, R.string.ui_open, R.string.ui_close, R.string.ui_save, R.string.ui_settings_short,
        R.string.ui_remove, R.string.ui_share, R.string.ui_print, R.string.ui_next,
        R.string.ui_apply, R.string.ui_search, R.string.ui_yes, R.string.ui_no,
        R.string.ui_back, R.string.ui_rename, R.string.ui_library, R.string.ui_recent,
        R.string.ui_bookmarks, R.string.ui_empty_library, R.string.ui_document_options,
        R.string.ui_page_fraction, R.string.ui_not_read, R.string.ui_resume,
        R.string.ui_library_search, R.string.ui_private_copies, R.string.ui_add_pdf,
        R.string.ui_open_pdf, R.string.ui_empty_bookmarks, R.string.ui_no_documents,
        R.string.ui_pdf_saved, R.string.ui_save_failed, R.string.ui_remove_library,
        R.string.ui_rename_document, R.string.ui_remove_copy, R.string.ui_remove_copy_warning,
        R.string.ui_no_picker, R.string.ui_import_pdf, R.string.ui_cannot_open,
        R.string.ui_to_library, R.string.ui_toggle_bookmark, R.string.ui_fullscreen,
        R.string.ui_reader_options, R.string.ui_exit_fullscreen, R.string.ui_choose_page,
        R.string.ui_previous_page, R.string.ui_page_jump_accessibility, R.string.ui_pages,
        R.string.ui_next_page, R.string.ui_first_render, R.string.ui_page_range,
        R.string.ui_jump_page, R.string.ui_pdf_view, R.string.ui_reflow_mode,
        R.string.ui_search_document, R.string.ui_outline, R.string.ui_reading_settings,
        R.string.ui_my_bookmarks, R.string.ui_page_overview, R.string.ui_pdf_tools,
        R.string.ui_document_info, R.string.ui_reset_zoom, R.string.ui_show_notes,
        R.string.ui_bookmark_help, R.string.ui_page_view, R.string.ui_continuous,
        R.string.ui_single_pages, R.string.ui_fit_page, R.string.ui_dark_interface,
        R.string.ui_invert_pdf, R.string.ui_keep_awake, R.string.ui_serif,
        R.string.ui_font_size, R.string.ui_night_warning, R.string.ui_checking_pdf,
        R.string.ui_pdf_password, R.string.ui_password_pdf, R.string.ui_password_privacy,
        R.string.ui_unlock, R.string.ui_password_rejected, R.string.ui_no_reflow_permission,
        R.string.ui_loading_text, R.string.ui_no_search_permission, R.string.ui_search_term,
        R.string.ui_no_hits, R.string.ui_hits, R.string.ui_search_hit,
        R.string.ui_no_embedded_outline, R.string.ui_please_wait, R.string.ui_processing_failed,
        R.string.ui_protected_copy_warning, R.string.ui_edited_copy_warning,
        R.string.ui_export_ready, R.string.ui_export_message, R.string.ui_import_copy,
        R.string.ui_import_failed, R.string.ui_page_example, R.string.ui_owner_password_required,
        R.string.ui_tools_safe_copies, R.string.ui_rotate_pages, R.string.ui_extract_pages,
        R.string.ui_delete_pages, R.string.ui_merge_pdfs, R.string.ui_split_pdf,
        R.string.ui_add_note, R.string.ui_fill_form, R.string.ui_protect_password,
        R.string.ui_highlight_text, R.string.ui_hand_sign, R.string.ui_edit_metadata,
        R.string.ui_rotation, R.string.ui_rotate_right, R.string.ui_rotate_left,
        R.string.ui_pages_order, R.string.ui_delete_from_copy, R.string.ui_note_page,
        R.string.ui_note_text, R.string.ui_protection_options, R.string.ui_password,
        R.string.ui_highlight_current, R.string.ui_word_sentence, R.string.ui_edit_title,
        R.string.ui_new_title, R.string.ui_edit_author, R.string.ui_author,
        R.string.ui_enter_value, R.string.ui_open_two_pdfs, R.string.ui_merge_library_order,
        R.string.ui_combine, R.string.ui_select_two_pdfs, R.string.ui_split_page_count,
        R.string.ui_number_example, R.string.ui_enter_number, R.string.ui_split_limit,
        R.string.ui_split_failed, R.string.ui_no_form_fields, R.string.ui_fill_copy,
        R.string.ui_create_copy, R.string.ui_form_load_failed, R.string.ui_info_unavailable,
        R.string.ui_properties, R.string.ui_share_pdf, R.string.ui_no_notes,
        R.string.ui_notes, R.string.ui_note_item, R.string.ui_note_title,
        R.string.ui_notes_unavailable, R.string.ui_signature_help, R.string.ui_redraw,
        R.string.ui_signature_title, R.string.ui_current_outline, R.string.ui_search_outline,
        R.string.ui_search_chapter, R.string.ui_no_outline, R.string.ui_no_chapters,
        R.string.ui_expand_children, R.string.ui_current_chapter, R.string.ui_jump_number,
        R.string.ui_collapse_chapter, R.string.ui_expand_chapter, R.string.ui_loading_pdf,
        R.string.ui_cancel_loading, R.string.ui_preparing_pages, R.string.ui_page_progress,
        R.string.ui_opening_document, R.string.ui_copy_progress, R.string.ui_copy_size,
        R.string.ui_signature_accessibility, R.string.ui_signature_cleared,
        R.string.ui_clear_signature, R.string.ui_highlight_denied, R.string.ui_extraction_denied,
        R.string.ui_search_single_line, R.string.ui_page_no_text, R.string.ui_rotated_text,
        R.string.ui_text_not_found, R.string.ui_draw_signature_first,
        R.string.ui_signature_coordinates, R.string.ui_signature_denied,
        R.string.ui_pdf_not_readable, R.string.ui_work_folder_failed, R.string.ui_page_outside,
        R.string.ui_rotated_page, R.string.ui_invalid_dimensions, R.string.ui_password_first,
        R.string.ui_password_file_first, R.string.ui_invalid_pdf, R.string.ui_unsupported_pdf,
        R.string.ui_pdf_document, R.string.ui_pdf_empty, R.string.ui_pdf_access_denied,
        R.string.ui_pdf_not_found, R.string.ui_pdf_open_failed, R.string.ui_loading_pdf_ellipsis,
        R.string.ui_page_unavailable, R.string.ui_render_failed, R.string.ui_pdf_page,
        R.string.ui_page_of, R.string.ui_this_page_unavailable, R.string.ui_untitled,
        R.string.ui_author_extraction_denied, R.string.ui_password_text, R.string.ui_text_read_failed,
        R.string.ui_password_check_failed, R.string.ui_unlock_failed, R.string.ui_wrong_password,
        R.string.ui_rotation_multiple, R.string.ui_keep_one_page, R.string.ui_merge_failed,
        R.string.ui_select_pdf, R.string.ui_xfa_merge, R.string.ui_enter_note,
        R.string.ui_properties_failed, R.string.ui_forms_failed, R.string.ui_xfa_unsupported,
        R.string.ui_notes_failed, R.string.ui_no_fillable_form, R.string.ui_select_form_field,
        R.string.ui_field_not_found, R.string.ui_field_group, R.string.ui_field_readonly,
        R.string.ui_signature_field_text, R.string.ui_field_value_unsupported,
        R.string.ui_protect_failed, R.string.ui_password_empty, R.string.ui_password_length,
        R.string.ui_edit_failed, R.string.ui_select_page, R.string.ui_duplicate_page,
        R.string.ui_selected_page_outside, R.string.ui_metadata_denied, R.string.ui_rotation_denied,
        R.string.ui_extract_pages_denied, R.string.ui_delete_pages_denied, R.string.ui_merge_denied,
        R.string.ui_notes_denied, R.string.ui_forms_denied, R.string.ui_protect_denied,
        R.string.ui_file_error, R.string.ui_reflow_no_text, R.string.ui_reflow_page,
        R.string.ui_invalid_path, R.string.ui_storage_folder_failed, R.string.ui_selected_file_open_failed,
        R.string.ui_import_cancelled, R.string.ui_pdf_too_large, R.string.ui_pdf_header_missing,
        R.string.ui_pdf_save_failed, R.string.ui_file_access_denied, R.string.ui_pdf_import_failed,
        R.string.ui_file_read_failed, R.string.ui_pdf_import_unavailable, R.string.ui_copy_delete_failed,
        R.string.ui_document_data_failed, R.string.ui_invalid_selection, R.string.ui_pages_bounds,
        R.string.ui_print_failed, R.string.ui_language, R.string.ui_language_de,
        R.string.ui_language_en, R.string.ui_language_fr, R.string.ui_language_es,
        R.string.ui_language_system, R.string.ui_dark_mode, R.string.ui_bookmark_page,
        R.string.ui_reload_pdf, R.string.ui_screenshot, R.string.ui_signature,
        R.string.ui_owner_edit_only, R.string.ui_screenshot_failed, R.string.ui_processing_pdf,
        R.string.ui_rotate_which_pages, R.string.ui_splitting_pdf, R.string.ui_print_denied,
        R.string.ui_enter_pages, R.string.ui_loading_page, R.string.ui_enter_page_number,
        R.string.ui_case_insensitive, R.string.ui_search_progress,
        R.string.ui_creator_footer, R.string.ui_about, R.string.ui_about_message
    )
}
