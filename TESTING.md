# Prüfstand · 13. Oktober 2026

## Automatisch erfolgreich

- Debug-APK und Instrumentation-APK gebaut.
- 22 JVM-Unit-Tests: Fortschritt, Seitenauswahl, Textverarbeitung und Page-Curl-Geometrie.
- Android Lint: keine Fehler; Hinweise zu Übersetzbarkeit, API-Deprecations und optionalen KTX-Erweiterungen bleiben.
- 31 Integrationstests jeweils auf Android 12 / API 31 und Android API 37 (Fold-Emulator):
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

## Bedienung geprüft

`tools/smoke_test.py` und `tools/reflow_smoke_test.py` prüfen über echte Android-UI-Interaktionen: PDF öffnen, scrollen, Position/Lesezeichen nach Prozess-Neustart, Vorschaubilder, Nachtmodus, Vollbild samt Verlassen, fingergeführtes Umblättern, Reflow, Suche, neue Notiz-Kopie und Android-Druckvorschau.

`tools/zen_ui_smoke_test.py` prüft alle vier Sprachwechsel samt Activity-Neustart, helle Zen-Farben und den sichtbaren Autor **Marcus Hoja**. `tools/search_cancel_smoke_test.py` prüft den tatsächlichen Abbruch einer laufenden 300-Seiten-Suche und eine direkt anschließende Suche mit Großbuchstaben gegen kleingeschriebenen PDF-Text.

Auch die tatsächlich aus GitHub Releases heruntergeladene APK wurde separat installiert, ihre veröffentlichte SHA-256-Prüfsumme verifiziert und beide UI-Abläufe auf API 37 ausgeführt. CI und lokale APKs haben unterschiedliche Entwicklersignaturen; die lokalen Instrumentation-Tests können deshalb nicht direkt an die CI-APK angehängt werden.

## Grenzen der Prüfung

Emulatoren, kein physisches Smartphone oder Drucker. Kein breiter PDF-Korpus, keine Acrobat-Paritätsprüfung, keine vollständige Untersuchung komplexer asiatischer Fonts, verschachtelter XFA/Formulare oder beschädigter PDFs. Die ausdrücklich in `README.md` fehlenden Funktionen sind nicht implementiert und werden nicht als getestet ausgewiesen.
