# PDFCraft Android

**APK-Updates ohne Deinstallation:** Einmalige Einrichtung des festen
Signierschlüssels über ein GitHub-Secret: siehe [`SIGNING.md`](SIGNING.md).

Eine native, smartphonegerechte PDF-Lese-App, inspiriert von [storytold/pdfcraft](https://github.com/storytold/pdfcraft). Eigenständige Android-Implementierung, keine offizielle ArtCraft-App und keine vollständige Portierung der Rust-Anwendung.

**Von Marcus Hoja.** Die Oberfläche verbindet die kompakte Bedienung aus MJ PDF mit einem ruhigen Zen-Stil: Salbei, warmes Papier und Braun, lokale Material-Symbols-Icons und ein eigenes Buch-/Blatt-App-Icon.

## APK herunterladen

Die jeweils aktuelle installierbare APK liegt unter [GitHub Releases](https://github.com/marcushoja/PDFCraft_Android/releases/latest): [PDFCraft.apk](https://github.com/marcushoja/PDFCraft_Android/releases/latest/download/PDFCraft.apk).

Jeder Push auf `main` baut und prüft die App automatisch und veröffentlicht einen neuen Release inklusive SHA-256-Prüfsumme. Es handelt sich zunächst um **entwicklersignierte Vorschau-Builds**, nicht um eine Play-Store-Veröffentlichung. Der CI-Signaturschlüssel wird wiederverwendet; falls dessen GitHub-Cache gelöscht wird, kann eine Neuinstallation erforderlich sein. Lokal gebaute APKs haben einen anderen Schlüssel als CI-APKs und lassen sich deshalb nicht direkt darüber installieren.

## Funktionen

- Lokale Bibliothek mit Vorschaubildern, Fortschritt und „Weiterlesen“.
- PDFs über die Android-Dateiauswahl, „Öffnen mit“ und „Teilen“ importieren.
- Lesestelle pro Dokument speichern: Seite, relative vertikale Position und Zoom. Identische Dateien werden anhand ihres SHA-256-Hashes erkannt; erneuter Import behält den Fortschritt.
- Fortlaufend vertikal lesen oder horizontal durch Einzelseiten wischen.
- Fingergeführtes Umblättern mit nichtlinear gekrümmtem, zylindrischem Bitmap-Mesh: sichtbare Papierrückseite, gespiegelter leichter Durchdruck, Faltkante und Schatten; Abschluss oder Zurückrollen beim Loslassen. Das Mesh nutzt den hardwarebeschleunigten Canvas-Pfad, wenn verfügbar; es ist kein eigener OpenGL-Renderer. Animation derzeit für ungezoomte Einzelseiten, Doppelseiten werden ohne Curl gewechselt.
- Fünf Leserlayouts unter **Einstellungen → Seitenansicht**: fortlaufend, Einzelseiten, zwei Seiten nebeneinander, Buch mit separatem Deckblatt, Comic/Webtoon ohne Zwischenräume. Unabhängige RTL-Leserichtung für Manga, passende Wischrichtung und Navigation; Text und Seitenbilder werden nicht gespiegelt. Gemischte Seitengrößen, ungerade Endseiten und Lesestellen beim Layout-/Größenwechsel werden berücksichtigt.
- Schnellere Seitenübersicht: begrenzter sichtbarer Ausschnitt, abbrechbare Vorschau-Aufträge, Wiederverwendung nativer PDF-Renderer, RAM-/Plattencache und keine vollständige Anmerkungsprüfung pro Thumbnail. Übersicht startet an der aktuellen Lesestelle; Bibliotheksmenüs haben einen transparenten Hintergrund über dem Cover.
- Teilen verwendet den ursprünglichen bzw. in der Bibliothek gewählten Dokumentnamen statt der internen UUID, ohne große PDFs dafür nochmals zu kopieren.
- Bibliothekscover füllen die Kachel ohne Verzerrung (mittiger Zuschnitt). Beim Umbenennen ist der bisherige Name bearbeitbar vorausgefüllt. **Lokale Kopie löschen** entfernt die private PDF-Kopie samt Lesefortschritt und Lesezeichen nach Bestätigung; die Originaldatei bleibt unverändert.
- **PDF scannen** über das Kamera-Symbol in der Bibliothek: Fotos mit der Kamera-App aufnehmen, vier Dokumentecken verschieben und perspektivisch geradeziehen. Die aufgenommenen Seiten werden in einer horizontalen Leiste gesammelt und als mehrseitige PDF in die Bibliothek übernommen. Verarbeitung und Export erfolgen lokal, ohne Konto oder Cloud-OCR. Perspektivkorrektur ist für flache Dokumente gedacht, nicht für gekrümmte Buchseiten; ohne OCR besteht die PDF aus Bildern.
  - Seitliche Helligkeits-/Kontrast-Presets und ein **Schwarz/Weiß**-Schalter mit Vorschau. **Weiter** übernimmt die aktuelle Seite und öffnet die nächste Kameraaufnahme oder Bildauswahl, abhängig von der zuletzt verwendeten Quelle; das Preset bleibt erhalten. **Exportieren** übernimmt ebenfalls eine noch bearbeitete Seite und erstellt die PDF. Keine automatische Folgeaufnahme-Checkbox und keine doppelten Aufnahmebuttons unten.
  - Thumbnails per langem Drücken und Ziehen umsortieren; ein Sechs-Punkte-Symbol neben der Seitennummer weist darauf hin. Das optisch kleinere **X** behält eine gut treffbare Touchfläche und eine Löschbestätigung.
  - **Bild auswählen** importiert vorhandene Bilder einzeln über die Android-Dateiauswahl in denselben Editor; Originalbilder bleiben unverändert. Die kreisförmige 3×-Lupe mit Fadenkreuz bleibt immer oben, auf der gegenüberliegenden Seite der aktiven Ecke. Die Icon-Leiste bietet **Drehen · Anpassen · Löschen · Weiter · Exportieren**. Nicht verfügbare Drei-Punkte-Menüs werden ausgegraut. Die Aufnahme selbst erfolgt weiterhin über die Kamera-App des Geräts, nicht über eine eigene Live-Kameraansicht.
  - Unfertige Scans bleiben privat als Entwurf gespeichert. Nach erfolgreicher Übernahme in die Bibliothek werden Entwurfsfotos entfernt; bei abgebrochener/fehlgeschlagener Übernahme bleiben sie wiederaufnehmbar. Bis zu 100 Seiten, Bilder mit maximal 2200 Pixeln Kantenlänge; Ausgabe auf A4 ohne Verzerrung, mit Weißrändern bei anderem Seitenverhältnis.
- Pinch-Zoom, Doppeltipp-Zoom, Seitenbreite oder ganze Seite einpassen.
- Seitenübersicht, direkter Seitensprung, eigene Lesezeichen.
- Echtes Vollbild inklusive Systemleisten, mit einer Schaltfläche zum Verlassen; dunkle Oberfläche, invertierte Nachtansicht und optional wach gehaltener Bildschirm.
- **Textmodus / Reflow:** echter eingebetteter PDF-Text als auswählbarer, umbrochener Text; einstellbare Schriftgröße und Serifenschrift, separate gespeicherte Text-Leseposition. Seitenweise Navigation. Scans ohne Text erhalten einen Hinweis statt eines vorgetäuschten Reflows.
- Abbrechbare Volltextsuche ohne Beachtung der Groß-/Kleinschreibung, mit Seitenfortschritt, Trefferliste und Markierungen im Textmodus; aufklappbares, durchsuchbares Inhaltsverzeichnis; Teilen, Screenshot und Android-Drucken.
- Fortschritts-Ladebildschirm beim Import, beim Vorbereiten großer Dokumente und bis die erste sichtbare Seite gerendert ist; jederzeit abbrechbar.
- Deutsch, Englisch, Französisch und Spanisch unter **Einstellungen → Sprache**. Standard: Deutsch; „Systemsprache“ folgt dem Gerät. PDF-Inhalt, Dateinamen und Notizen werden nicht übersetzt.
- Suchbare Cover-Bibliothek mit „Zuletzt“, „Bibliothek“ und „Lesezeichen“, Umbenennen und Entfernen lokaler Kopien. Die Bedienung und Farbgebung orientieren sich an [MJ PDF](https://play.google.com/store/apps/details?id=com.gitlab.mudlej.MjPdfReader); kein MJ-PDF-Code übernommen.
- PDF-Werkzeuge: Seiten drehen, extrahieren/neu ordnen, löschen, zusammenführen und aufteilen; Notizen hinzufügen/lesen, Text markieren, Standardformulare ausfüllen, Metadaten ändern und Passwortschutz.
- Eigenes Icon-Raster für alle PDF-Werkzeuge. Markieren mit sechs Farben, Live-Vorschau und drei Modi: Hervorheben, Unterstreichen, Durchstreichen. Farb-/Moduswahl bleibt gespeichert. Im Reflow erscheint „Text markieren“ im Auswahlmenü. Markiert werden alle passenden Textstellen auf der aktuellen Seite; mehrzeilige/gedrehte Texte und Scans sind noch nicht unterstützt.
- Direkte Textauswahl auf der gerenderten PDF-Seite: lang drücken, Auswahlgriffe verschieben, Kopieren/Alles auswählen/Markieren. Diese Markierung verwendet ausschließlich die ausgewählten Glyphen, auch über mehrere Zeilen, statt alle gleichen Wörter zu markieren. Unterstützt Seitenbeschnitt, 0°/90°/180°/270°-Seitendrehung, Zoom und Scrollen. Auswahl bleibt auf einer Seite; individuell gedrehte/geschrägte oder vertikale Schrift wird noch ausgelassen, Scans benötigen OCR.
- Große Bild-PDFs werden zuerst nativ angezeigt; die vollständige Anmerkungsprüfung blockiert die erste Seite nicht mehr. Seitengrößen und geprüfte Darstellungen liegen im privaten, automatisch begrenzten Dokumentcache (bis 512 MiB, sieben Tage). Ein zusätzlicher 32-MiB-RAM-Cache beschleunigt das Wiederöffnen bereits gerenderter Seiten. Entschlüsselte `unlocked-*`-Ansichten werden nicht dauerhaft bzw. dokumentübergreifend gecacht. Anmerkungen können beim ersten Öffnen kurz nach der Seite erscheinen.
- Handschriftliche sichtbare Unterschrift per Finger; **keine kryptografische Signatur**.
- Passwortgeschützte PDFs zum Lesen entsperren; Passwort wird nicht gespeichert, verschlüsselte Originalkopie bleibt erhalten. Temporäre entschlüsselte Ansicht wird beim Schließen gelöscht und nach einem Prozessabbruch beim nächsten Start bereinigt. Autor-Berechtigungen werden für Textzugriff/Druck berücksichtigt; Bearbeitung entsperrter Dateien derzeit nur mit Eigentümerpasswort.
- Lokale private Kopien: Originaldateien werden niemals verändert. Keine Internetberechtigung, Konten, Werbung oder Telemetrie. Cloud-Dateianbieter können beim Import selbst Netzwerkzugriff benötigen.

**Keine vollständige PdfCraft-Parität:** weiterhin fehlen OCR, zuverlässige Bearbeitung bestehender PDF-Texte, Office-Konvertierung, sichere Schwärzung, kryptografische Signaturen, Ebenen-/Anhangsverwaltung, PDF/A-/Preflight-Werkzeuge, XFA, vollständige Kommentar-Threads und weitere Acrobat-Pro-Funktionen. Direkte Textauswahl funktioniert für eingebetteten Text; die Grenzen stehen oben. Die Nachtansicht invertiert auch Bilder.

Die vollständige Liste der noch offenen Funktionen steht in [`FEATURES_TODO.md`](FEATURES_TODO.md), der Prüfstand in [`TESTING.md`](TESTING.md).

**Sichere Originale, begrenzte Bearbeitung:** Werkzeuge erstellen neue Kopien, keine inkrementellen Updates. Bestehende digitale Signaturen können dadurch ungültig werden. Bearbeitete Kopien werden ohne ursprüngliche Verschlüsselung gespeichert, außer beim ausdrücklichen Passwortschutz. Extraktion/Seitenlöschung repariert nicht alle Verweise des Inhaltsverzeichnisses oder Formulare zu entfernten Seiten und ist keine sichere Schwärzung. Highlighting unterstützt zunächst ungedrehte Textseiten. Neu geschützte Kopien erlauben Lesen/Drucken, sperren aber Textextraktion/Bearbeitung. Zusammenführen ist auf importierte Bibliotheksdateien begrenzt; Aufteilen erstellt lokale Bibliothekskopien.

## Bauen

Android Studio mit JDK 17 oder neuer, Android SDK 36 und Build Tools 36.0.0. Mindestversion auf dem Smartphone: **Android 8.0 (API 26)**.

Projektordner in Android Studio öffnen; `local.properties` auf das eigene SDK zeigen lassen (nicht im Git). Danach:

```sh
./gradlew assembleDebug
./gradlew testDebugUnitTest lintDebug
./gradlew connectedDebugAndroidTest
```

Unter Windows entsprechend `gradlew.bat`. APK: `app/build/outputs/apk/debug/app-debug.apk`.

## Aufbau

Kotlin, native Android-Views und AndroidX Activity. Androids `PdfRenderer` rendert im Hintergrund; PDFBox Android verarbeitet Text und PDF-Kopien lokal. Keine WebView und kein Server. Ein begrenzter Bitmap-Cache und bedarfsgerechtes Nachrendern halten den Speicherverbrauch kontrolliert; maximale Vergrößerung 5×, Importlimit 500 MiB. Bei extremem Zoom ist die Renderauflösung aus Speichergründen begrenzt; noch kein gekacheltes Rendering.

- `MainActivity.kt`: Bibliothek, Android-Dateiauswahl und Leseoberfläche.
- `PdfReaderView.kt`: Rendering, Layout, Gesten, Leseposition.
- `DocumentStore.kt`: private Importe, Duplikaterkennung und Metadaten.
- `ThumbnailLoader.kt`: Hintergrund-Vorschaubilder mit Speicher-/Festplatten-Cache.
- `ReadingProgress.kt`: unabhängig getestete Normalisierung der Leseposition.
- `LibraryView.kt`: suchbare Cover-Bibliothek.
- `PdfTextService.kt`, `ReflowView.kt`: Extraktion, Suche, Inhaltsverzeichnis und Textmodus.
- `PdfTools.kt`, `PdfMarkup.kt`: Organisation, Formulare, Notizen, Markierungen und sichtbare Unterschriften.

Eigene Lesezeichen sind App-Metadaten, keine Änderungen innerhalb der PDF. Dokumente und Fortschritt werden nicht per Android-Backup exportiert; Deinstallation löscht die lokale Bibliothek.

## Tests

JUnit-Tests prüfen Fortschrittswerte, Seitenauswahl und Textverarbeitung. Android-Integrationstests prüfen echten Import, Duplikate, Fortschritt, Lesezeichen, sichere Entfernung, ungültige Dateien, Rendering, Zoom-Gesten, Reflow, Suche, Inhaltsverzeichnis, PDF-Werkzeuge, Markierungen, Unterschriften, Formulare, Passwörter und unveränderte Originale.

`tools/smoke_test.py` ergänzt einen UI-Test auf einem laufenden Emulator: Seitensprung, Lesezeichen, Prozess-Neustart, Wiederaufnahme und Screenshots. Zuerst Integrationstests ausführen, damit die Test-PDF existiert. `ADB` kann auf eine alternative ADB-Datei zeigen.

```sh
python3 tools/smoke_test.py --output /tmp/opencode/pdfcraft-smoke
python3 tools/reflow_smoke_test.py
python3 tools/zen_ui_smoke_test.py
python3 tools/search_cancel_smoke_test.py
```

## Lizenz

Eigener Code unter MIT, siehe `LICENSE`. Kein Quellcode oder Markenmaterial aus PdfCraft oder MJ PDF übernommen. AndroidX, PDFBox Android und Gradle Wrapper unter Apache-2.0; siehe `NOTICE`. Die Render-Engine ist Teil des Android-Systems.

Material Symbols werden über Iconify bezogen und als lokale Vektorpfade gebündelt (Google, Apache-2.0); kein Netzwerkzugriff zur Laufzeit und keine Icon-Schrift nötig.
