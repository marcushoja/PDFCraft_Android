# Prüfstand · 13. Oktober 2026

## PDF-Seitentipps und Vollbildsymbol · 6. November 2026

Die Seitenübersicht steht oben neben der Ansichtsdrehung, Vollbild unten neben der
Seitenzahl. Ein bestätigter Einzeltipp in die linke PDF-Hälfte blättert zurück,
in die rechte Hälfte vorwärts, auch im Vollbild. Dabei wird dieselbe Navigation
wie bei den Pfeiltasten verwendet. Vollbild wird ausschließlich über die Symbole
geöffnet bzw. verlassen; Doppeltipp-Zoom und Textauswahl bleiben unverändert.

`ReaderTapNavigationIntegrationTest` prüft die vertauschten Symbolpositionen,
beide Tipprichtungen, Dokumentgrenzen und unveränderte Leisten-Sichtbarkeit im
Normal- und Vollbildmodus sowie das Verlassen über das Vollbildsymbol.

Lokal erfolgreich: Debug-APK, Test-APK, JVM-Tests und Android Lint mit dem Linux-SDK.
Der Android-Test wurde kompiliert, aber nicht ausgeführt: Die ADB-Geräteabfrage
antwortet nicht und wurde nach zehn Sekunden abgebrochen.

## PDFPauli und Einhorn-Türkis · 5. November 2026

App-Label, Bibliothek, Über-Dialog, Unterstützung und Update-Texte heißen in allen
vier Sprachen PDFPauli. Das App-Icon steht links neben dem Bibliothekstitel und im
Über-Dialog. Helle und dunkle Oberflächen sowie Launcher-Hintergründe verwenden
Türkis aus dem Einhorn statt Grün. Paketkennung, Signierung und Release-URLs bleiben
unverändert, einschließlich des kompatiblen Dateinamens `PDFCraft.apk`.

Ein neuer JVM-Test prüft die Einhorn-Akzentfarben und mindestens 4,5:1 Kontrast auf
Papier-, Karten- und getönten Flächen beider Themes. Ein neuer Android-Test prüft
App-Label, Icon und Titel im Bibliothekskopf beider Themes sowie den Über-Dialog.
Bestehende Sprach-, Unterstützungs- und Annotationstests verwenden den neuen Namen.

Lokal erfolgreich: Debug-APK, Test-APK, alle 54 JVM-Tests und Android Lint.
Android-Tests wurden nur kompiliert, nicht ausgeführt: Die ADB-Geräteabfrage läuft
in eine Zeitüberschreitung. Eine visuelle Geräteprüfung steht noch aus.

## Blätterbereich: äußere zwanzig Prozent

Blättern kann links bei 0–20 % und rechts bei 80–100 % der Reader-Breite beginnen,
einschließlich der Grenzen. Der Bereich hängt nicht mehr von der eingepassten
Papierbreite ab und ist nicht mehr auf 48 dp begrenzt. Das gilt auch für große
Ansichten sowie RTL und Doppelseiten. Die Mitte startet weiterhin keinen
Seitenwechsel; Zoom-Sperre und bestehende Umblätter-Animation bleiben unverändert.

Drei JVM-Tests prüfen beide Randzonen einschließlich Grenzen bei 320, 1080, 1440
und 2560 Pixeln, die gesperrte Mitte und ungültige Koordinaten. Die vorhandenen
Android-Tests ergänzen echte Faltungen an den 20%-Grenzen sowie ignorierte Gesten
knapp außerhalb; der RTL-Doppelseitentest beginnt ebenfalls an diesen Grenzen.

Erfolgreich: Debug-/Test-APK, alle 53 JVM-Tests und Lint. Android-Tests wurden
kompiliert, aber wegen nicht antwortendem ADB nicht ausgeführt.

## Gespeicherte Ansichtsdrehung und vereinfachte Menüs · 5. November 2026

Die Bibliothek bietet im Hamburger nur Updates und „Über PDFCraft“. Links daneben
schaltet eine Sonne-/Mond-Schaltfläche dieselbe globale Oberfläche um wie „Dunkler
Modus“ im Lesemenü. Die Ansichtseinstellungen bleiben ausschließlich im Lesemenü.
Dort entfallen „PDF öffnen“, „Vollbild“ und „Über PDFCraft“; der Vollbildknopf oben
bleibt erhalten. „Seite merken“ bleibt im Menü, nicht mehr als Toolbar-Symbol.

Das neue Toolbar-Symbol dreht die Darstellung aller Seiten pro Klick um 90°.
`viewRotation` wird in denselben Dokumentmetadaten wie Leseseite, Offset und Zoom
gespeichert (alte Einträge: 0°). Der Dateihash und die PDF-Bytes bleiben unverändert;
Teilen und Drucken verwenden weiterhin die tatsächliche Datei. Die bleibende
PDF-Drehung im Werkzeugmenü ist davon unabhängig. Im Textmodus wechselt der
Drehknopf zunächst zurück zur PDF-Ansicht.

Native Seitengrößen bleiben ungeändert im Größen-Cache; das Layout nutzt bei 90°/
270° vertauschte Maße. Render- und RAM-Cache-Schlüssel enthalten den Ansichtswinkel;
spät eintreffende Bilder einer alten Drehung werden nicht angezeigt. Text-Hit-Tests
verwenden die inverse Drehung, Auswahlrechtecke die Vorwärtsdrehung. PDF-Quads für
Markierungen bleiben in Originalkoordinaten. Umblätter-Gesten bleiben am Rand und
Zoom bleibt auf die aktuelle Seite begrenzt.

JVM-Tests ergänzen Drehzyklus, Normalisierung, Koordinaten-Rücktransformation,
Native-Render-Matrizen ohne Beschnitt/Spiegelung und die beiden Menü-Inhalte.
Android-Tests ergänzen farbige Seitenbilder und Textauswahl nach Drehung, den
Toolbar-Klick mit Speicherung/Wiederöffnung pro Dokument sowie den Theme-Schalter
vor dem Hamburger und die neuen Beschreibungen in allen vier Sprachen. Die Reader-
und Sprach-Smoke-Skripte wurden an die neuen Zugänge angepasst und syntaxgeprüft.

Lokal erfolgreich: Debug- und Test-APK gebaut, alle 50 JVM-Tests und Android Lint.
Android-Integrationstests wurden kompiliert, aber nicht auf einem Gerät ausgeführt:
ADB antwortet weiterhin nicht. Die visuelle Prüfung bleibt daher offen.

## Menüs, manuelle Updates und lokale Bearbeitung · 5. November 2026

Das Bibliotheksmenü bietet „Auf Updates prüfen“ und die Einstellungen. Die manuelle
Prüfung umgeht die Tagesfrist und zeigt auch bereits geschlossene Update-Angebote
wieder an; offline/fehlerhaft/aktuell erhalten eine Rückmeldung. Oben entfällt das
Herz; unten bleibt „Buy me a coffee“ mit dem bisherigen Unterstützungsdialog.
„PDF-Werkzeuge“ steht im Lesemenü zuerst. Drehung bietet das gesamte Dokument oder
ausgewählte Seiten, danach den Winkel.

Bearbeitungen aktualisieren dieselbe private Bibliothekskopie samt festem Eintrag
und Namen. Der Inhalts-Hash wird separat gespeichert; alte Einträge ohne dieses
Feld verwenden weiterhin ihre bisherige Hash-ID. Gleiche bearbeitete Inhalte
werden beim erneuten Import erkannt; der unveränderte externe Ursprung kann erneut
separat importiert werden. Seitenauswahl/-löschung ordnet Lesezeichen und Leseseite
neu zu. Aufteilen/Zusammenführen erzeugen weiterhin eigenständige Dokumente.

Die neue PDF wird neben dem Ziel zwischengespeichert und synchronisiert. Beim
atomaren Austausch bleibt eine temporäre Sicherung bis zum Metadaten-Commit
erhalten; ein Commit-Fehler stellt die bisherigen Bytes wieder her. Der geänderte
Dateizeitstempel verhindert alte Render-/Text-/Vorschaubilder. Es gibt keine
Versionshistorie und kein Undo. Externe Originale werden nicht überschrieben.

Neue JVM-Tests prüfen manuellen Update-Status, übergangene Tagesfrist/geschlossene
Hinweise, Menü-Reihenfolge, Dateiaustausch bei noch geöffnetem Leser, Fehler-Rollback,
ungültige/abgebrochene Ausgaben und das Entfernen temporärer Dateien. Android-Tests
ergänzen Gesamtdrehung samt Berechtigungen, wiederholte Bearbeitung ohne neue
Bibliothekseinträge, Hash-Deduplizierung und Zuordnung von Lesezeichen/Lesestelle.
Die fünf betroffenen UI-Smoke-Skripte wurden auf das neue Verhalten angepasst und
auf Python-Syntax geprüft; ein Gerätetest ist damit nicht ersetzt.

Lokal erfolgreich: Debug-APK und Test-APK gebaut, alle 46 JVM-Tests und Android Lint.
Die Android-Integrationstests wurden nur kompiliert, nicht ausgeführt: ADB antwortet
weiterhin nicht auf die Geräteabfrage. Eine visuelle Prüfung steht noch aus.

## Automatische Update-Prüfung · 5. November 2026

Beim Öffnen prüft ein separater Hintergrund-Executor das neueste öffentliche
GitHub-Release, sobald ein validierter Internetzugang besteht. Nur eine erfolgreiche
Prüfung setzt die 24-Stunden-Pause; offline, Zeitüberschreitungen, API-Fehler und
unvollständige Releases erlauben einen neuen Versuch beim nächsten Öffnen. Ein
bekanntes Update bleibt lokal zwischengespeichert. Geschlossene Versionshinweise
bleiben auch nach einem Neustart verborgen; neuere Releases werden wieder angezeigt.

Der Hinweis erscheint ausschließlich innerhalb der Bibliothek, nicht über einer
PDF. „Herunterladen“ öffnet die offizielle, versionsgebundene GitHub-APK-Adresse im
Browser. Es gibt weder einen APK-Download im Hintergrund noch einen eigenen
Installationsdialog oder eine Berechtigung zur Paketinstallation. Die App benötigt
jetzt Internet-/Netzwerkstatus-Berechtigungen für diese Abfrage; Dokumente bleiben
lokal und werden nicht mitgesendet.

Neun JVM-Tests prüfen Zeitabstand und Grenzzeit, Offline-Wiederholung, Fehler und
zwischengespeicherte Ergebnisse, Versionsvergleich, dauerhaftes Schließen,
fehlende Releases, zurückgestellte Geräteuhren und erlaubte Downloadadressen.
Vier Android-Tests prüfen Release-JSON einschließlich ungültiger/unvollständiger
Antworten, Browser-Intent ohne Dokumentdaten, die beiden Hinweisaktionen in beiden
Designs und Übersetzungen in allen vier Sprachen. Der echte GitHub-Endpunkt wurde
lesend geprüft: `build-28` mit passender `PDFCraft.apk` und numerischer Build-ID.

Lokal erfolgreich: Debug-APK, Test-APK, alle 38 JVM-Tests und Android Lint.
Die Android-Tests wurden kompiliert, aber nicht ausgeführt: ADB antwortet hier
weiterhin nicht auf die Geräteabfrage. Ein visueller Gerätetest steht noch aus.

## Randgesten und Zoom · 4. November 2026

Umblättern startet nur im schmalen linken/rechten Randbereich. Ziehen an der
Randmitte rollt die Seite horizontal, Ziehen nahe den Ecken weiterhin diagonal.
Der Schlagschatten hat keine harte Schnittkante mehr. Vergrößerte Seiten begrenzen Verschieben und
Nachlauf auf die aktuelle Seite bzw. Doppelseite, auch bei RTL und vertikalem Lesen.

`PdfCurlIntegrationTest` ergänzt Prüfungen für Wischen aus der Mitte, horizontale
Faltung am Seitenrand und überlange horizontale/vertikale Zoom-Gesten samt Nachlauf
in Einzel-, Doppel-, Buch- und Endlosansicht, jeweils mit und ohne RTL.
`PdfSpreadIntegrationTest` prüft zusätzlich ignorierte Wischgesten aus der Mitte.

Lokal erfolgreich: Debug-APK, Test-APK, alle 28 JVM-Tests und Android Lint.
Die Android-Integrationstests wurden kompiliert, aber in diesem Durchlauf nicht
ausgeführt: ADB antwortete nicht auf die Geräteabfrage. Eine visuelle Prüfung auf
einem Gerät steht deshalb noch aus.

Die Rückseite zeigt anschließend wieder den Inhalt der aktuellen Seite: durch die
Mesh-Geometrie spiegelverkehrt, neutral abgedunkelt, entsättigt und kontrastärmer.
Eine auf 768 Pixel begrenzte, zwischengespeicherte Textur mildert feine Details ohne
Blur pro Animationsbild. Gesten, Faltung und Schatten bleiben unverändert.
Ein zusätzlicher JVM-Test prüft die gespiegelte Pixelreihenfolge für beide
Blätterrichtungen; ein Android-Test prüft Rückseitenkontrast, erhaltene Details,
Textur-Wiederverwendung und den Nachtmodus.
Auch diese Ergänzung: Debug- und Test-APK, alle 29 JVM-Tests und Lint erfolgreich;
Android-Tests wegen nicht antwortendem ADB nur kompiliert, nicht ausgeführt.

## Bibliotheksansicht und Scan-Vorschau

Am 14. Oktober 2026 erfolgreich: Debug- und Test-APK gebaut, JVM-Tests und Lint sowie
je 14 gezielte Integrationstests auf API 31 und API 37 (28 Testausführungen).

`LibraryLayoutIntegrationTest` prüft den Wechsel zwischen Raster und Liste, gespeicherte
Ansichts-/Sortiereinstellungen, Namen und Hinzufüge-Datum in beiden Richtungen sowie
Öffnen und Dokumentoptionen in der Liste. Der Unterstützungslink steht zusätzlich im
Fußbereich bei den Autor-Credits.

Die kompakte Liste wird zusätzlich auf maximal 80 dp Zeilenhöhe bei normaler
Schriftgröße, separate Dokumentoptionen und Icon-Schalter mit lesbaren
Bedienungshilfe-Beschreibungen geprüft. Layout-, Overlay- und Aktionstests liefen
auf API 31 und API 37 erfolgreich (je drei Tests); Build, JVM-Tests und Lint ebenfalls.

`ScanCropViewIntegrationTest` prüft mit echten Zwei-Finger-Touch-Ereignissen den Zoom
der bestätigten Vorschau ohne Klick oder Eckpunktänderung. Der Aktivitätstest prüft
denselben Ablauf nach dem Übernehmen: kein Rücksprung in die Eckpunktbearbeitung;
„Anpassen“ bleibt der explizite Einstieg.

Nach dem Übernehmen steht an derselben Position „Weiter“ mit Doppelpfeil (»). Die
Aktivitätstests fangen externe Intents ab und prüfen, dass erst der Tipp darauf die
zuletzt verwendete Kamera bzw. Bildauswahl öffnet; die Kameraquelle bleibt auch nach
Entwurfswiederherstellung erhalten. Der Unterstützungstest prüft das Herz vor dem
Link im Fußbereich.

Die Seitenleiste zeigt beim Ziehen eine senkrechte Einfügelinie statt einer
markierten Zielkachel. Tests prüfen die sichtbaren Pixel in hellem/dunklem Design,
Positionen vor/zwischen/hinter den Seiten, Scroll-Offsets und das Ausblenden der
Linie. Der Einfüge-Resolver prüft beide Ziehrichtungen und unveränderte Positionen.
Am 15. Oktober 2026 erfolgreich: Build, JVM-Tests und Lint sowie je zehn gezielte
Seitenleisten-/Scanner-Aktivitätstests auf API 31 und API 37.

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
- 118 Integrationstests einschließlich zweier optionaler Großdatei-Benchmarks auf Android 12 / API 31 und Android API 37 (Fold-Emulator). Im aktuellen Durchlauf waren die Großdatei-Fixtures nicht vorhanden; diese zwei optionalen Benchmarks wurden übersprungen:
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
  - kachelfüllende, unverzerrte Cover, vorausgefüllter und markierter Name beim Umbenennen sowie Abbrechen/Bestätigen von „Lokale Kopie löschen“; die Originaldatei bleibt bytegenau erhalten.

## Bedienung geprüft

`tools/smoke_test.py` und `tools/reflow_smoke_test.py` prüfen über echte Android-UI-Interaktionen: PDF öffnen, scrollen, Position/Lesezeichen nach Prozess-Neustart, Vorschaubilder, Nachtmodus, Vollbild samt Verlassen, fingergeführtes Umblättern, Reflow, Suche, neue Notiz-Kopie und Android-Druckvorschau.

`tools/zen_ui_smoke_test.py` prüft alle vier Sprachwechsel samt Activity-Neustart, helle Zen-Farben und den sichtbaren Autor **Marcus Hoja**. `tools/search_cancel_smoke_test.py` prüft den tatsächlichen Abbruch einer laufenden 300-Seiten-Suche und eine direkt anschließende Suche mit Großbuchstaben gegen kleingeschriebenen PDF-Text.

`tools/markup_smoke_test.py` prüft das PDF-Werkzeug-Icon-Raster, die Farbauswahl, Unterstreichen und das Öffnen einer echten neuen annotierten PDF. Zusätzliche Markup-Tests prüfen RGB, die drei Annotationstypen, unveränderte Originale und die native Anzeige über eine temporäre kompatible Darstellungskopie.

`tools/pdf_selection_smoke_test.py` prüft langes Drücken auf der gerenderten Seite, das native Kopieren-/Markieren-Menü und eine exakte ausgewählte Markierung. Integrationstests prüfen verschiebbare Griffe, Zoom, Original-Berechtigungen auch nach Entschlüsselung, Zwischenablage-Inhalt und native Glyphen-/Beschnittkoordinaten bei allen vier Seitendrehungen. Individuell gedrehte/geschrägte Schrift, vertikale Schrift und seitenübergreifende Auswahl sind nicht implementiert.

`tools/layouts_smoke_test.py` prüft die Auswahl der neuen Layouts über die echten Einstellungen, Doppelseiten-Navigation, separate Buchdeckblätter, RTL-Wischrichtung, ungerade Endseiten und die Wiederherstellung nach Prozessneustart.

## Kamera-Scanner

`ScanProcessingIntegrationTest` prüft die Perspektivkorrektur anhand einer synthetischen
Trapezaufnahme, ungültige Eckpunkte, Farbmodi, EXIF-Ausrichtung, Seitensortierung der
PDF-Ausgabe sowie Abbruch ohne unvollständige PDF oder veränderte Quellbilder.
Kameraaufnahmen werden über die Kamera-App mit einer privaten FileProvider-URI
angefordert; PDFCraft benötigt dafür keine eigene Kamera-Berechtigung.
Scans enthalten Bildseiten, keinen OCR-Textlayer. Automatische Rand-Erkennung und
Entzerrung gekrümmter Buchseiten sind nicht Bestandteil dieser Version.

`ScanActivityIntegrationTest` prüft wiederaufgenommene Kameraausgaben, mehrere Seiten,
persistierte Reihenfolge und Entwurfswiederherstellung. `ScanCropViewIntegrationTest`
prüft die verschiebbaren Eckpunkte; `ScanPageStripIntegrationTest` prüft Auswahl,
seitenbezogene Löschaktionen und die Reihenfolgenauflösung beim Ziehen.
`tools/scanner_smoke_test.py` nutzt die tatsächliche Emulator-Kamera und prüft
Eckpunkt-Ziehen, Presets, Übernehmen per Haken ohne Folgeaufnahme und manuelles Hinzufügen über die oberen Icons,
Drag-and-drop, X-Löschbestätigung, Drehen/Bearbeiten, Prozessneustart und die
mehrseitige benannte PDF in der Bibliothek einschließlich Entwurfsbereinigung.

`ScanImageImportIntegrationTest` prüft private JPEG-/PNG-Kopien ohne Veränderung der
Originale, ungültige Bilder, Abbruch und Schutz vorhandener Zieldateien. Zusätzliche
Crop-Tests prüfen echte lokale Pixelvergrößerung in der Lupe, Fadenkreuz, Presets,
Bildränder, kleine Ansichten und zusammengefasste Touch-Ereignisse beim Loslassen.
`tools/scanner_gallery_smoke_test.py` prüft die echte Android-Bildauswahl, Icon-Leiste,
Drehen vor dem Übernehmen, unveränderte Quelldatei und PDF-Export einschließlich
noch bearbeiteter Seiten. Übernehmen öffnet weder die Bildauswahl noch die Kamera.
Zusätzliche Tests prüfen die stets obere Lupe bei allen vier Ecken,
gegenseitig ausschließende Graustufen-/Schwarz/Weiß-Schalter samt Rückkehr zu Farbe, kleinere X-Symbole mit unverändertem Touchziel, den
Sechs-Punkte-Verschiebehinweis, ausgegraute Menüs und das Entfernen der Checkbox.
`ScanIntroTextIntegrationTest` prüft die vier echten Inline-Icons und lesbare
Screenreader-Texte in allen vier Sprachen. Die Aktivitätstests prüfen außerdem,
dass Anpassen während der Bearbeitung deaktiviert und nach dem Übernehmen wieder aktiv ist.

`SupportIntegrationTest` prüft das grüne Unterstützungs-Icon, den freiwilligen Hinweis,
Abbrechen ohne externe Aktion, Öffnen erst nach ausdrücklicher Bestätigung und die
Übersetzungen in allen vier Sprachen. Das Browser-Intent enthält ausschließlich den
bereits in der README hinterlegten öffentlichen Stripe-Link, keine PDF-Daten oder
Dateifreigaben. Tests lösen keine echte Zahlung aus.

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
