# Einmalige Einrichtung für installierbare APK-Updates

Der feste Projektschlüssel ist vorbereitet. Die bisherigen Builds bis einschließlich
Build 9 hatten wechselnde Signaturen: Beim einmaligen Wechsel auf den festen
Schlüssel ist eine Neuinstallation nötig. Vorher wichtige PDFs und Daten sichern.
Danach lassen sich neue Releases ohne vorherige Deinstallation installieren.

## Nur ein GitHub-Secret

1. Öffne [Repository → Settings → Secrets and variables → Actions](https://github.com/marcushoja/PDFCraft_Android/settings/secrets/actions).
2. Klicke **New repository secret**.
3. Name: **`PDFCRAFT_SIGNING_BUNDLE`**.
4. Füge den vollständigen Inhalt von `.signing-private/github-secret.txt` als Wert ein.
5. Speichern. Danach unter **Actions → Build, test and publish APK → Run workflow** den Build starten.

Der Wert enthält den Schlüssel samt Einstellungen; weitere Passwort-Secrets sind
nicht erforderlich. Niemals in Issues, Chats, Releases oder Git einfügen.

## Sicherung

Den gesamten Ordner **`.signing-private`** sicher und zusätzlich außerhalb dieses
Projektordners sichern. Er wird von Git ignoriert und nicht hochgeladen.
`github-secret.txt` enthält ebenfalls den vollständigen privaten Schlüssel und
muss wie ein Passwort behandelt werden. GitHub zeigt gespeicherte Secrets später
nicht wieder im Klartext an.

Nur der **öffentliche Zertifikat-Fingerabdruck** steht in
`config/signing-certificate.sha256`. Der Workflow prüft ihn sowohl am Schlüssel
als auch an der fertigen APK. Bei fehlendem oder falschem Secret bricht er ab,
statt einen anderen Schlüssel zu erzeugen. Temporäre Schlüsseldateien liegen
außerhalb des Checkouts und werden nach dem Build entfernt.

Lokale Entwickler-Builds ohne Signing-Umgebung behalten ihren lokalen Debug-Key;
sie sind keine signaturkompatiblen Updates der GitHub-APK.
