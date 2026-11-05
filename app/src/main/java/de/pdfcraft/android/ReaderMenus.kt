package de.pdfcraft.android

internal object ReaderMenus {
    fun library(updateLabel: String) = listOf(
        ReaderAction("updates", updateLabel, "reload"),
        ReaderAction("about", "Über PDFCraft", "info"),
    )

    fun pdf(reflow: Boolean) = listOf(
        ReaderAction("tools", "PDF-Werkzeuge", "tools"),
        ReaderAction("dark", "Dunkler Modus", "moon"),
        ReaderAction("search", "Im Dokument suchen", "search"), ReaderAction("jump", "Zu Seite springen", "jump"),
        ReaderAction("bookmark", "Seite merken", "bookmark"),
        ReaderAction("text", if (reflow) "PDF-Ansicht" else "Textmodus / Reflow", "text"), ReaderAction("pages", "Seitenübersicht", "outline"),
        ReaderAction("share", "Teilen", "share"), ReaderAction("print", "Drucken", "print"),
        ReaderAction("signature", "Unterschrift", "signature"),
        ReaderAction("reload", "PDF neu laden", "reload"), ReaderAction("info", "Dokumentinformationen", "info"),
        ReaderAction("screenshot", "Screenshot", "camera"), ReaderAction("zoom", "Zoom zurücksetzen", "crop"),
        ReaderAction("outline", "Inhaltsverzeichnis", "outline"), ReaderAction("bookmarks", "Meine Lesezeichen", "bookmark"),
        ReaderAction("notes", "Notizen anzeigen", "notes"), ReaderAction("settings", "Einstellungen", "settings"),
    )
}
