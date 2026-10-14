# Prüfstand · 13. Oktober 2026

## Feste APK-Signierung · 14. Oktober 2026

`python3 -m unittest discover -s tools -p 'test_prepare_signing.py' -v`
prüft mit einem wegwerfbaren Testschlüssel sechs Fälle: wiederholte Wiederherstellung,
fehlendes/ungültiges Secret, falsches Zertifikat, unerlaubte Umgebungsvariablen-Injektion
und Schutz vorhandener Schlüsseldateien.

Zwei echte App-Builds mit Versionscodes 10 und 11 wurden lokal signiert und mit
`apksigner verify --print-certs` gegen den festgelegten öffentlichen Fingerabdruck
geprüft. Zusätzlich wurde auf API 37 eine separate, gleich signierte Test-App von
Version 1 auf 2 aktualisiert; ihre private Testdatei blieb dabei erhalten. Die
vorhandene PDFCraft-Installation musste für diesen Update-Test nicht gelöscht werden.

Einrichtung des einmaligen GitHub-Secrets: [`SIGNING.md`](SIGNING.md).

## Automatisch erfolgreich

- Debug-APK und Instrumentation-APK gebaut.
- 28 JVM-Unit-Tests: Fortschritt, Seitenauswahl, Textverarbeitung, gekrümmtes Mesh und Gruppierung der Buch-/Doppelseitenlayouts.
- Android Lint: keine Fehler; Hinweise zu Übersetzbarkeit, API-Deprecations und optionalen KTX-Erweiterungen bleiben.
- 84 Integrationstests einschließlich zweier optionaler Großdatei-Benchmarks auf Android 12 / API 31 und Android API 37 (Fold-Emulator):
  - echter Datei-/Content-URI-Import, Duplikate, Fortschritt und Lesezeichen;
  - echte PDF-Render-Engine, Seitensprünge, kurze letzte Seite, Größe/Rotation;
  - Doppeltipp und Mehrfinger-Zoom;
  - nativer Reflow, Schriftwechsel und gespeicherte Textposition;
  - Suche und PDF-Inhaltsverzeichnis;
  - PDF-Organisation, Metadaten, Notizen, Formulare und Passwörter;
  - Markierungen, handschriftliche Unterschriften und unveränderte Originale.
  - fingergeführte Faltung mit sichtbarer Rückseite, Abbruch und endgültiger Position beim Loslassen;
  - abbrechbare Suche und Import, Ladefortschritt, aufklappbare Kapitelkarten;
  - Deutsch/Englisch/Französisch/Spanisch, Template- und Dateinamen-Erhaltung sowie Autor-Credits.
  - Doppelseiten, separates Deckblatt, ungerade Endseiten, RTL-Wischen/Glyphenauswahl, gemischte Seitengrößen und Lesestellen nach Größenwechsel;
  - sichtbare gekrümmte Papierrückseite und Nachtfarben auch auf älterem Canvas;
  - eine native Renderer-Instanz für acht Vorschauen, Auftragsabbruch, stabile wiederholte Bindung, 300-Seiten-Übersicht mit begrenzter Anzahl sichtbarer Zellen;
  - transparenter Cover-Menühintergrund und ursprüngliche Unicode-Dateinamen beim Teilen, einschließlich unveränderter Dateiinhalte.

## Bedienung geprüft

`tools/smoke_test.py` und `tools/reflow_smoke_test.py` prüfen über echte Android-UI-Interaktionen: PDF öffnen, scrollen, Position/Lesezeichen nach Prozess-Neustart, Vorschaubilder, Nachtmodus, Vollbild samt Verlassen, fingergeführtes Umblättern, Reflow, Suche, neue Notiz-Kopie und Android-Druckvorschau.

`tools/zen_ui_smoke_test.py` prüft alle vier Sprachwechsel samt Activity-Neustart, helle Zen-Farben und den sichtbaren Autor **Marcus Hoja**. `tools/search_cancel_smoke_test.py` prüft den tatsächlichen Abbruch einer laufenden 300-Seiten-Suche und eine direkt anschließende Suche mit Großbuchstaben gegen kleingeschriebenen PDF-Text.

`tools/markup_smoke_test.py` prüft das PDF-Werkzeug-Icon-Raster, die Farbauswahl, Unterstreichen und das Öffnen einer echten neuen annotierten PDF. Zusätzliche Markup-Tests prüfen RGB, die drei Annotationstypen, unveränderte Originale und die native Anzeige über eine temporäre kompatible Darstellungskopie.

`tools/pdf_selection_smoke_test.py` prüft langes Drücken auf der gerenderten Seite, das native Kopieren-/Markieren-Menü und eine exakte ausgewählte Markierung. Integrationstests prüfen verschiebbare Griffe, Zoom, Original-Berechtigungen auch nach Entschlüsselung, Zwischenablage-Inhalt und native Glyphen-/Beschnittkoordinaten bei allen vier Seitendrehungen. Individuell gedrehte/geschrägte Schrift, vertikale Schrift und seitenübergreifende Auswahl sind nicht implementiert.

`tools/layouts_smoke_test.py` prüft die Auswahl der neuen Layouts über die echten Einstellungen, Doppelseiten-Navigation, separate Buchdeckblätter, RTL-Wischrichtung, ungerade Endseiten und die Wiederherstellung nach Prozessneustart.

## Große Bild-PDFs

`PdfLargeLoadBenchmark` nutzt optional eine synthetische PDF mit 32 JPEG-Seiten und 150,14 MiB (nicht im Repository). Auf dem API-37-Emulator: erste native Seite **2.341 ms**, Hintergrundprüfung abgeschlossen nach **7.393 ms**; Wiederöffnen mit Cache: **129 ms**, Seitengrößen nach **60 ms**. Diese Werte messen die Reader-Pipeline, **ohne Import/Kopieren und ohne UIAutomator-Overhead**; sie sind keine Zusage für beliebige PDFs oder Geräte.

Die Testdatei lässt sich mit `python3 tools/create_large_image_pdf.py --output /tmp/opencode/images-150mb.pdf` erzeugen (Pillow erforderlich) und als `cache/images-150mb.pdf` in die Debug-App übertragen. Dann den Test `de.pdfcraft.android.PdfLargeLoadBenchmark` ausführen; Messwerte stehen unter `PDF_LARGE_BENCH` im Logcat. Das Skript überschreibt keine vorhandenen Dateien.

Die 14 Cache-Tests prüfen unter anderem Parallelzugriffe, geänderte Dateien, beschädigte Cache-Einträge, Referenzschutz, separate Druckdarstellungen und den Ausschluss entschlüsselter Ansichten. Fehlt die große Testdatei, wird ausschließlich der optionale Benchmark übersprungen.

`PdfLargeThumbnailBenchmark` misst zusätzlich acht Vorschauen derselben 150-MiB-Bild-PDF bei leerem Thumbnail-Cache und beim erneuten Laden vom Plattencache. Er prüft genau einen nativen Dokument-Open beim ersten Durchlauf und keinen beim zweiten. Beide optionalen Großdatei-Tests werden ohne die synthetische Testdatei übersprungen; keine Behauptung über die Geschwindigkeit beliebiger PDFs oder Geräte.

Gemessener API-37-Durchlauf: erste Vorschau **228 ms**, acht Vorschauen **777 ms**,
**ein** Renderer-Open; erneutes Laden der acht Vorschauen vom Plattencache **13 ms**,
**kein** Renderer-Open. „Leer“ bezieht sich auf den App-Thumbnail-Cache, nicht auf den
Betriebssystem-Dateicache. Auf API 31 wurden die zwei optionalen Großdatei-Tests
ohne Fixture übersprungen; alle übrigen Integrationstests wurden ausgeführt.

**Zwischenablage-Test:** Headless-Emulatoren können den App-Clip durch einen leeren „host clipboard“-Clip ersetzen. Für die isolierten Clipboard-Läufe wurde die Emulator-Brücke über `-append-userspace-opt androidboot.qemu=0` bei einem Kaltstart deaktiviert, ohne die Host-Zwischenablage oder globale Emulator-Einstellungen zu verändern. Der Test verlangt den tatsächlichen Text „Hello“ und die Clip-Bezeichnung „PDF“, nicht nur einen erfolgreichen Menüaufruf. Grundlage: [AOSP ClipboardService](https://android.googlesource.com/platform/frameworks/base/+/android-12.0.0_r1/services/core/java/com/android/server/clipboard/ClipboardService.java).

Auch die tatsächlich aus GitHub Releases heruntergeladene APK wurde separat installiert, ihre veröffentlichte SHA-256-Prüfsumme verifiziert und beide UI-Abläufe auf API 37 ausgeführt. CI und lokale APKs haben unterschiedliche Entwicklersignaturen; die lokalen Instrumentation-Tests können deshalb nicht direkt an die CI-APK angehängt werden.

## Grenzen der Prüfung

Emulatoren, kein physisches Smartphone oder Drucker. Kein breiter PDF-Korpus, keine Acrobat-Paritätsprüfung, keine vollständige Untersuchung komplexer asiatischer Fonts, verschachtelter XFA/Formulare oder beschädigter PDFs. Die ausdrücklich in `README.md` fehlenden Funktionen sind nicht implementiert und werden nicht als getestet ausgewiesen.
