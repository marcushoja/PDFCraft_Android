# PDFCraft Android

Eine native, smartphonegerechte PDF-Lese-App, inspiriert von [storytold/pdfcraft](https://github.com/storytold/pdfcraft). Eigenständige Android-Implementierung, keine offizielle ArtCraft-App und keine vollständige Portierung der Rust-Anwendung.

## APK herunterladen

Die jeweils aktuelle installierbare APK liegt unter [GitHub Releases](https://github.com/marcushoja/PDFCraft_Android/releases/latest): [PDFCraft.apk](https://github.com/marcushoja/PDFCraft_Android/releases/latest/download/PDFCraft.apk).

Jeder Push auf `main` baut und prüft die App automatisch und veröffentlicht einen neuen Release inklusive SHA-256-Prüfsumme. Es handelt sich zunächst um **entwicklersignierte Vorschau-Builds**, nicht um eine Play-Store-Veröffentlichung. Der CI-Signaturschlüssel wird wiederverwendet; falls dessen GitHub-Cache gelöscht wird, kann eine Neuinstallation erforderlich sein. Lokal gebaute APKs haben einen anderen Schlüssel als CI-APKs und lassen sich deshalb nicht direkt darüber installieren.

## Erste Version

- Lokale Bibliothek mit Vorschaubildern, Fortschritt und „Weiterlesen“.
- PDFs über die Android-Dateiauswahl, „Öffnen mit“ und „Teilen“ importieren.
- Lesestelle pro Dokument speichern: Seite, relative vertikale Position und Zoom. Identische Dateien werden anhand ihres SHA-256-Hashes erkannt; erneuter Import behält den Fortschritt.
- Fortlaufend vertikal lesen oder horizontal durch Einzelseiten wischen.
- Pinch-Zoom, Doppeltipp-Zoom, Seitenbreite oder ganze Seite einpassen.
- Seitenübersicht, direkter Seitensprung, eigene Lesezeichen.
- Ablenkungsfreies Lesen per Antippen, dunkle Oberfläche, invertierte Nachtansicht und optional wach gehaltener Bildschirm.
- Lokale private Kopien: Originaldateien werden niemals verändert. Keine Internetberechtigung, Konten, Werbung oder Telemetrie. Cloud-Dateianbieter können beim Import selbst Netzwerkzugriff benötigen.

**Noch nicht enthalten:** Textsuche/-auswahl, PDF-Inhaltsverzeichnis, Passwort-Eingabe, Annotationen, Bearbeitung, OCR, echte Text-Reflow-Ansicht und Cloud-Synchronisation. Passwortgeschützte oder beschädigte PDFs erhalten eine Fehlermeldung. Die Nachtansicht invertiert auch Bilder.

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

Kotlin, native Android-Views und AndroidX Activity. Androids `PdfRenderer` rendert im Hintergrund. Keine WebView, kein Server, keine externe PDF-Laufzeit. Ein begrenzter Bitmap-Cache und bedarfsgerechtes Nachrendern halten den Speicherverbrauch kontrolliert; maximale Vergrößerung 5×, Importlimit 500 MiB. Bei extremem Zoom ist die Renderauflösung aus Speichergründen begrenzt; noch kein gekacheltes Rendering.

- `MainActivity.kt`: Bibliothek, Android-Dateiauswahl und Leseoberfläche.
- `PdfReaderView.kt`: Rendering, Layout, Gesten, Leseposition.
- `DocumentStore.kt`: private Importe, Duplikaterkennung und Metadaten.
- `ThumbnailLoader.kt`: Hintergrund-Vorschaubilder mit Speicher-/Festplatten-Cache.
- `ReadingProgress.kt`: unabhängig getestete Normalisierung der Leseposition.

Eigene Lesezeichen sind App-Metadaten, keine Änderungen innerhalb der PDF. Dokumente und Fortschritt werden nicht per Android-Backup exportiert; Deinstallation löscht die lokale Bibliothek.

## Tests

JUnit-Tests prüfen ungültige und nicht-endliche Fortschrittswerte. Android-Integrationstests prüfen echten PDF-Import, Duplikate, Fortschritt, Lesezeichen, sichere Entfernung, ungültige Dateien und Rendering/Navigation mit einer generierten achtseitigen PDF.

`tools/smoke_test.py` ergänzt einen UI-Test auf einem laufenden Emulator: Seitensprung, Lesezeichen, Prozess-Neustart, Wiederaufnahme und Screenshots. Zuerst Integrationstests ausführen, damit die Test-PDF existiert. `ADB` kann auf eine alternative ADB-Datei zeigen.

```sh
python3 tools/smoke_test.py --output /tmp/opencode/pdfcraft-smoke
```

## Lizenz

Eigener Code unter MIT, siehe `LICENSE`. Kein Quellcode oder ArtCraft-Markenmaterial aus dem Vorbild übernommen. AndroidX-Komponenten unter Apache-2.0; Gradle Wrapper unter Apache-2.0. Die PDF-Engine ist Teil des Android-Systems.
