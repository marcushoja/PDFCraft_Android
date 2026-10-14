# Noch offene Funktionen

Stand: Zen-Reader-Version. Dies ist eine eigenständige Android-App, **keine vollständige PdfCraft-/Acrobat-Portierung**.

## Lesen und Smartphone-Bedienung

- [x] Text direkt auf gerenderten PDF-Seiten auswählen: lang drücken, verschiebbare Griffe, Kopieren und exakte farbige Markierung. Glyphenkoordinaten berücksichtigen Beschnitt, Seitendrehung, Zoom und Scrollposition.
- [ ] Auswahl über Seitengrenzen und Unterstützung für individuell gedrehte/geschrägte oder vertikale Schrift; solche Glyphen werden momentan ausgelassen. Scans brauchen weiterhin OCR.
- [ ] Interaktive interne/externe PDF-Links samt Zurück-/Vorwärts-Verlauf.
- [ ] Rand-Erkennung und automatische Randbeschneidung, ohne Dokumentänderungen.
- [x] Zwei-Seiten-Ansicht, RTL-Leserichtung, Buchlayout mit separatem Deckblatt und lückenloses Comic-/Webtoon-Layout; echte Dokumentseitennummern und Lesestellen bleiben erhalten.
- [ ] Automatisches Scrollen, Zoom-Sperre, Helligkeitssteuerung und konfigurierbare Vollbild-Shortcuts.
- [x] Geometrisch gekrümmtes, zylindrisches Bitmap-Mesh für Page-Curl, mit fingergeführter Faltung, Rückseite, Durchdruck und Schatten. Canvas-Mesh wird bei Hardwarebeschleunigung über den GPU-Pfad gezeichnet.
- [ ] Animiertes Umblättern ganzer Doppelseiten und tief gezoomter Seiten; der Mesh-Curl ist derzeit auf ungezoomte Einzelseiten begrenzt. Kein eigener OpenGL-Surface-Renderer.
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
- [x] Farbpalette und direkte Glyphenauswahl für Markierungen, auch auf gedrehten Seiten und über Zeilen hinweg.
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
- [x] Lokaler Kamera-Scanner mit manueller Vier-Ecken-Auswahl, Perspektivkorrektur und mehrseitiger Bild-PDF-Ausgabe.
- [ ] Automatische Dokumentrand-Erkennung und Entzerrung gekrümmter Buchseiten beim Scannen.
  - Bewusst nicht als kleiner Zusatz in diesem Update umgesetzt: Dafür sind eine OCR-Engine, lokale Sprachmodelle, ein verlässlicher Textlayer-Export sowie eigene Speicher-/Abbruchtests notwendig. Keine Cloud-OCR oder scheinbar durchsuchbare Bild-PDF als Ersatz.
- [ ] Office-Dateien nach PDF und layouttreuer Word-/Excel-/Bild-/Text-Export.
- [ ] Sichere Schwärzung und Dokumentbereinigung. **Seitenlöschen oder optisches Überdecken ist keine sichere Schwärzung.**
- [ ] PDF/A, PDF/X und PDF/UA, Preflight, Barrierefreiheitsprüfung sowie Tag-/Lesereihenfolge-Bearbeitung.
- [ ] Farbprofile, Druckvorstufe und Prüfung auf echten Druckern.

## Qualität und Veröffentlichung

- [ ] Breiter Regressionstest-Korpus, Fuzzing fehlerhafter PDFs und vergleichende Fidelity-Tests gegen etablierte Viewer.
- [ ] Tests auf physischen Smartphones, insbesondere Android 8–11, niedriger RAM, SD-Karten und große Bibliotheken.
- [ ] Vollständige Accessibility-Prüfung mit TalkBack, großen Schriften und RTL-Oberflächen.
- [ ] Weitere Sprachen und vollständige Prüfung sämtlicher dynamischer/backendseitiger Fehlermeldungen.
- [x] Dauerhafter Signaturschlüssel aus einem GitHub-Secret, mit Zertifikatprüfung vor der Veröffentlichung.
- [ ] Release-Härtung und optional Play-Store-Veröffentlichung.

## Bereits umgesetzt bzw. Teil dieses Updates

PDF-Import, private Bibliothek, Leseposition, PDF/Reflow, Seitenübersicht und Lesezeichen; Suche ohne Beachtung der Groß-/Kleinschreibung mit Abbruch; Fortschritts-Ladeansicht; Vollbild; fingergeführtes Umblättern mit sichtbarer Rückseite; Icon-Aktionsmenü und aufklappbares Inhaltsverzeichnis; Zen-Farbgebung/App-Icon; Deutsch/Englisch/Französisch/Spanisch; grundlegende Organisation, Formulare, Notizen, Markierungen, sichtbare Unterschrift, Passwort-Lesen, Metadaten, Teilen, Screenshot und Druckvorschau.
