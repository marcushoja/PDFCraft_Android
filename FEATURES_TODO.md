# Noch offene Funktionen

Stand: Zen-Reader-Version. Dies ist eine eigenständige Android-App, **keine vollständige PdfCraft-/Acrobat-Portierung**.

## Lesen und Smartphone-Bedienung

- [ ] Text direkt auf gerenderten PDF-Seiten auswählen, mit verschiebbaren Griffen und zuverlässiger Glyphen-/Koordinatenzuordnung; derzeit erfolgt Auswahl im Reflow.
- [ ] Interaktive interne/externe PDF-Links samt Zurück-/Vorwärts-Verlauf.
- [ ] Rand-Erkennung und automatische Randbeschneidung, ohne Dokumentänderungen.
- [ ] Zwei-Seiten-Ansicht, RTL-Leserichtung und weitere Comic-/Buchlayouts.
- [ ] Automatisches Scrollen, Zoom-Sperre, Helligkeitssteuerung und konfigurierbare Vollbild-Shortcuts.
- [ ] Geometrisch stärker gekrümmtes Papier-/GPU-Mesh für Page-Curl; der aktuelle Canvas-Effekt besitzt bereits eine fingergeführte Faltkante, Rückseite und Schatten.
- [ ] Hochauflösendes gekacheltes Rendering für tiefen Zoom und sehr große Seiten.
- [ ] Verbesserte Lesereihenfolge bei mehrspaltigen oder komplexen PDFs, Layouttests für CJK/RTL und vertikalen Text.
- [ ] Optional durchgehender Reflow über Seitengrenzen statt seitenweiser Textnavigation.
- [ ] Zitat-Bildkarten mit anpassbaren Farben, Autor und Buchtitel wie im Referenz-Screenshot.

## Bibliothek

- [ ] Ordneransicht und optionale PDF-Erkennung auf Gerätespeicher/SD-Karten; bisher private importierte Kopien.
- [ ] Manuelle Statusgruppen: ungelesen, in Arbeit, pausiert, beendet und abgebrochen.
- [ ] Sortierauswahl, Listen-/Rasterumschaltung und Sammlungen/Tags.
- [ ] Sicherer Import von Online-PDFs über ausdrücklich freigegebene Links.
- [ ] Inkognito-Lesen ohne Verlauf sowie lokaler Export/Import von Bibliotheksmetadaten und Einstellungen.
- [ ] Mehrere geöffnete Dokumente/Tabs und angepasste Tablet-/Fold-Layouts.

## PDF-Werkzeuge und Kommentare

- [ ] Bestehenden PDF-Text wirklich bearbeiten und neue Inhalte layouttreu setzen.
- [ ] Direkt platzierbare Formen, Stempel, Freihand-Notizen und Bild-/Text-Anmerkungen.
- [ ] Farbpalette und Auswahl-Werkzeug für Markierungen, auch auf gedrehten Seiten und über Zeilen hinweg.
- [ ] Kommentar-Threads, Antworten, Bearbeiten/Löschen vorhandener Annotationen und Kommentar-Export.
- [ ] Undo/Redo für PDF-Bearbeitung mit sicherer inkrementeller/atomarer Speicherung und Crash-Recovery.
- [ ] Bei Seitenentnahme/-löschung sämtliche Links, Seitenlabels, Inhaltsverzeichnis- und Formularreferenzen korrekt neu zuordnen.
- [ ] Mehrfach-Export beim Teilen/Aufteilen mit benutzerdefinierter Reihenfolge und Dateinamen.
- [ ] Originalverschlüsselung bei Bearbeitung bewahren; derzeit neue Bearbeitungskopien ohne alte Verschlüsselung, mit ausdrücklichem Hinweis.
- [ ] Erweiterter Berechtigungsdialog und optionaler, frei konfigurierbarer Passwortschutz.

## Formulare, Signaturen und Spezial-PDFs

- [ ] Formulare direkt auf der PDF-Seite bedienen, passend zu Checkbox-/Radio-/Auswahlfeldtypen.
- [ ] Erweiterte Formular-Appearances und Unicode-Font-Unterstützung sowie sichere PDF-JavaScript-/Berechnungsfunktionen.
- [ ] XFA-Unterstützung oder ein geeigneter alternativer Rendering-/Formular-Backend.
- [ ] Kryptografische Signaturen, Zertifikatsprüfung, Vertrauensketten, Zeitstempel und langfristige Validierung. Die bestehende Finger-Unterschrift ist nur sichtbar, nicht kryptografisch.
- [ ] Ebenen ein-/ausblenden, eingebettete Anhänge anzeigen/exportieren und detaillierte Font-/Sicherheitsinformationen.

## Konvertierung, OCR und professionelle Workflows

- [ ] Vollständig lokale OCR für Scans, inklusive mehrsprachiger Modelle und durchsuchbarer PDF-Ausgabe.
- [ ] Office-Dateien nach PDF und layouttreuer Word-/Excel-/Bild-/Text-Export.
- [ ] Sichere Schwärzung und Dokumentbereinigung. **Seitenlöschen oder optisches Überdecken ist keine sichere Schwärzung.**
- [ ] PDF/A, PDF/X und PDF/UA, Preflight, Barrierefreiheitsprüfung sowie Tag-/Lesereihenfolge-Bearbeitung.
- [ ] Farbprofile, Druckvorstufe und Prüfung auf echten Druckern.

## Qualität und Veröffentlichung

- [ ] Breiter Regressionstest-Korpus, Fuzzing fehlerhafter PDFs und vergleichende Fidelity-Tests gegen etablierte Viewer.
- [ ] Tests auf physischen Smartphones, insbesondere Android 8–11, niedriger RAM, SD-Karten und große Bibliotheken.
- [ ] Vollständige Accessibility-Prüfung mit TalkBack, großen Schriften und RTL-Oberflächen.
- [ ] Weitere Sprachen und vollständige Prüfung sämtlicher dynamischer/backendseitiger Fehlermeldungen.
- [ ] Dauerhafter Release-Signaturschlüssel statt cachebasierter Vorschau-Signatur, Release-Härtung und optional Play-Store-Veröffentlichung.

## Bereits umgesetzt bzw. Teil dieses Updates

PDF-Import, private Bibliothek, Leseposition, PDF/Reflow, Seitenübersicht und Lesezeichen; Suche ohne Beachtung der Groß-/Kleinschreibung mit Abbruch; Fortschritts-Ladeansicht; Vollbild; fingergeführtes Umblättern mit sichtbarer Rückseite; Icon-Aktionsmenü und aufklappbares Inhaltsverzeichnis; Zen-Farbgebung/App-Icon; Deutsch/Englisch/Französisch/Spanisch; grundlegende Organisation, Formulare, Notizen, Markierungen, sichtbare Unterschrift, Passwort-Lesen, Metadaten, Teilen, Screenshot und Druckvorschau.
