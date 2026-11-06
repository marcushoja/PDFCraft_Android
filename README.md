# PDFPauli Android

[![☕ Buy me a coffee](https://img.shields.io/badge/%E2%98%95%20Buy%20me%20a%20coffee-147D96?style=for-the-badge)](https://buy.stripe.com/5kQ00c1yP9PQfDP6vV9bO04?client_reference_id=pdfcraft_android)

[Download PDFCraft.apk](https://github.com/marcushoja/PDFCraft_Android/releases/latest/download/PDFCraft.apk)

Many hours of development and testing have gone into PDFPauli. If you enjoy the app, you are welcome to support the project voluntarily. Your support helps with further development. Thank you!

PDFPauli uses turquoise from its unicorn icon in both light and dark themes. The app icon also appears beside the library title and in **About PDFPauli**. The existing application ID, repository and `PDFCraft.apk` release filename remain unchanged for update compatibility.

**Buy me a coffee** beneath the author credits opens a friendly support message. The confirmation in that dialog opens the existing Stripe support page in your browser only when you choose to continue. There is no separate support icon next to the library menu. Support is optional and does not unlock features or affect access to your PDFs.

## Download the APK

Get the latest installable APK from [GitHub Releases](https://github.com/marcushoja/PDFCraft_Android/releases/latest): [PDFCraft.apk](https://github.com/marcushoja/PDFCraft_Android/releases/latest/download/PDFCraft.apk).

Every push to `main` automatically builds and checks the app and publishes a release with its SHA-256 checksum. These are **developer-signed preview builds**, not Play Store releases. GitHub builds use the fixed project signing key from the `PDFCRAFT_SIGNING_BUNDLE` secret; its certificate is checked before publication. Releases from build 11 onward support normal updates without uninstalling. Older releases may require a one-time reinstall: save important documents first. Local builds without the release signing environment use a different debug key and cannot update GitHub builds directly. See [SIGNING.md](SIGNING.md).

On opening the app, PDFPauli checks GitHub Releases in the background when Internet access is available, at most once every 24 hours after a successful check. If offline or a check fails, it silently tries again the next time the app is opened. A small notice in the library offers **Download**, which opens the official version-specific APK URL in your browser. Android still asks you to confirm installation. Closing a notice hides that release permanently, but not future releases. There are no update dialogs over an open PDF and no background APK downloads.

The library hamburger menu offers **Backup & restore**, **Settings**, **Check for updates** and **About PDFPauli**. The sun/moon button immediately to its left switches the app theme. Reading settings are also available in the opened PDF's menu. An explicit update check bypasses the daily interval and also offers a previously dismissed update, with feedback if the app is current, offline, or the check fails.

## Features

### Library and reading

- Private local library with cover thumbnails, reading progress and resume actions.
- Nested virtual folders with breadcrumb navigation, creation, renaming and moving. Folder operations do not move or duplicate PDF bytes. **Library** shows folders and PDFs at the current level; **All PDFs**, **Recent**, **Bookmarks** and name searches work across folders. Search results include the folder path.
- Folders appear first in the same scrolling list/grid as PDFs, with matching row/tile sizes, a large folder icon and direct PDF/subfolder counts. Counts exclude nested descendants; folder options stay in the same position as PDF options.
- **Move to…** in document options, or hold a PDF to select several and move them together. The destination browser can create folders. Folder options distinguish dissolving a folder (keep contents) from deleting its entire subtree after confirmation. External originals are never changed.
- Icon buttons switch between grid and compact list views and sort the library by name or date added, ascending or descending. List rows use small covers with a separate options button. View and sorting preferences are remembered.
- Import PDFs through Android's file picker, **Open with** and **Share**. Originals remain unchanged.
- Save each document's page, relative vertical position, zoom and viewing rotation. SHA-256 deduplication preserves progress when the same file is imported again.
- The reader toolbar has a **Rotate view 90° clockwise** button instead of the bookmark icon. Each click rotates every page's displayed orientation, cycling back after four clicks; the angle is restored per document on reopening. This changes neither the PDF bytes nor shared/printed output. Permanent page rotation remains a separate PDF tool. Bookmarks remain available in the reader menu.
- Five reading layouts: continuous vertical scrolling, single pages, side-by-side spreads, books with a separate cover, and gapless comic/webtoon scrolling. Independent right-to-left navigation for manga; text and page images are not mirrored. Mixed page sizes, unpaired final pages and saved positions are preserved when changing layouts or viewport size.
- Finger-controlled page curling with a nonlinear cylindrical bitmap mesh, visible paper backside, faint mirrored ink, crease and shadows. Swipes can start anywhere in the outer 20% of the reader width on either side, without a density-dependent cap; the middle 60% does not start a page turn. Release commits the turn or rolls it back. The mesh uses the hardware-accelerated Canvas path when available, not a dedicated OpenGL renderer. Curl animation currently applies to unzoomed single pages; spreads change without curl.
- Faster page overview with a bounded viewport, cancelable thumbnail requests, reused native PDF renderers, RAM/disk caches and no full annotation inspection per thumbnail. It opens at the current reading position.
- Covers fill their tiles without stretching, using centered cropping and rounded image corners. The options button stays transparent over the cover.
- Renaming starts with the existing name as editable, selected text. Sharing exposes the original or library-chosen name rather than the internal file identifier, without copying large PDFs again.
- **Delete local copy** removes the private PDF, reading progress and bookmarks after confirmation. The original file is kept.
- Pinch zoom, double-tap zoom, fit width or fit the entire page; page overview, direct page jump and app bookmarks.
- True fullscreen including system bars, controlled only by its bottom navigation icon and fullscreen exit icon. Page overview is in the top toolbar. Tap the left/right PDF half to go to the previous/next page without toggling fullscreen. Dark interface, inverted night rendering and optional keep-screen-awake behavior.
- **Text mode / Reflow:** selectable, rewrapped embedded PDF text with font-size and serif options, page navigation and a separately saved reading position. Image-only scans show an explanation rather than fake reflow.
- Cancelable, case-insensitive full-document search with progress and results; search highlights in reflow; expandable, searchable table of contents; sharing, screenshots and Android printing.
- Cancelable progress screens for import, large-document preparation and first-page rendering.
- German, English, French and Spanish under **Settings → Language**. German is the default; **System language** follows the device. PDF contents, filenames and notes are never translated.
- Searchable **Recent**, **Library** and **Bookmarks** views. The interface is inspired by [MJ PDF](https://play.google.com/store/apps/details?id=com.gitlab.mudlej.MjPdfReader); no MJ PDF code is used.

### Camera and image scanner

- Open **Scan PDF** using the library's camera icon. Take photos through the device's camera app or choose existing images with Android's file picker.
- Drag four document corners for perspective correction. A circular 3× magnifier with crosshair stays at the top, opposite the active corner's side.
- Side controls provide brightness/contrast presets, **Grayscale** and **Black/white** with preview. The two monochrome modes are mutually exclusive; tapping the active one again restores color.
- **✓ Apply** saves the current page without opening another capture or picker, then becomes **» Continue**: tap it to reopen the last-used camera or image picker. The upper camera/image icons let you change the source. **Export** also applies an unfinished edit before creating the PDF.
- Pinch to zoom and drag to pan the confirmed scan preview without re-entering corner editing. Use **Adjust** explicitly to edit the corners again.
- The labeled action bar provides **Rotate · Adjust · Delete · ✓ Apply · Export**. Adjust is disabled and dimmed while the corners are already being edited; unavailable overflow menus are also dimmed.
- The introductory text embeds the same local icons used by the toolbar and includes readable screen-reader descriptions. There is no automatic-next-photo checkbox or duplicate capture controls below.
- Collected pages appear in a horizontal strip. Hold and drag to reorder; a six-dot indicator beside the page number makes this discoverable. A vertical insertion line between thumbnails shows the exact drop position, including while scrolling. The smaller **X** retains a usable touch target and asks for confirmation before removal. Pages can also be rotated or edited again.
- Drafts are saved privately and can be resumed after an app restart. Draft photos are removed only after successful library import; failed or canceled imports keep the draft available.
- Up to 100 pages, with processed image edges limited to 2200 pixels. PDF output uses A4 pages without stretching, adding white margins for other aspect ratios.
- Processing and export are local, with no account or cloud OCR. Perspective correction targets flat documents, not curved book pages. Without OCR, the exported PDF contains images, not searchable text.

### Library backup and restore

- Open **Backup & restore** from the library menu or settings. **Create backup** saves one dated `.pdfpauli` file through Android's file picker, including all PDFs, folders, reading positions, bookmarks, embedded annotations/notes and reader/library settings. Choose local storage, an SD card or an available cloud file provider. Scanner drafts, caches, temporary decrypted views and passwords are excluded.
- Optional password protection uses AES-256-GCM with a fresh salt and PBKDF2-HMAC-SHA256. Use a long, unique password and keep it somewhere safe: forgotten passwords cannot be recovered. Leaving both password fields empty produces an unencrypted backup; anyone with that file can read its contents.
- Restore first authenticates and validates the entire archive and PDF checksums. **New collection** preserves existing PDFs, settings and progress, even for identical PDFs. **Replace library** requires a second confirmation and offers creating a backup first; it restores settings too. New PDF files are staged without overwriting current ones and one metadata commit publishes the result.
- Progress and cancellation are available. Keep the app open until completion. Backups need temporary free internal storage, and restoring needs room for both the archive and extracted PDFs. Limits: 10,000 PDFs, 10,000 folders, 64 folder levels, 500 MiB per PDF and 32 GiB of PDF data per backup. A new collection adds one folder level. Manual backups only; automatic scheduling is not included.
- The last successful backup time is shown on this device. Copy important backups off the device and periodically test restoration. Failed writes are not reported as successful; a provider may leave an incomplete file after process interruption, which cannot be restored. See [BACKUP_FORMAT.md](BACKUP_FORMAT.md) for the versioned format.

### PDF tools

- Rotate, extract/reorder, delete, merge and split pages; add/read notes, mark text, fill standard forms, edit metadata and apply password protection.
- **PDF tools** is the first item in the reader menu. Rotation offers **Entire PDF** or selected pages before choosing the angle.
- The reader menu omits duplicate **Open PDF**, **Fullscreen** and **About** actions. Fullscreen is in the bottom navigation, and dark mode is available in both the reader menu and the library header.
- Edits update the existing private library copy rather than creating another library entry each time. Names, reading progress and bookmarks are retained; page selection/reordering remaps bookmarks and the reading page. A staged, synced file replaces the local PDF atomically, with rollback if metadata saving fails. Imported originals outside the app are never modified. Splitting and merging still produce separate documents by design.
- Icon-grid tool menus. Markup offers six colors and highlight, underline and strikeout, with preview and remembered choices. Reflow selection includes **Mark text**. Query-based marking matches text on the current page; scans require OCR.
- Direct selection on rendered pages: long press, move selection handles, copy, select all or mark. Markup uses only the selected glyphs, including multiline selections, instead of marking every identical word. Supports crop offsets, 0°/90°/180°/270° page rotations, zoom and scrolling. Selection remains on one page; individually rotated/skewed or vertical glyphs are skipped.
- Image-heavy PDFs display natively first, without waiting for complete annotation inspection. Page sizes and prepared viewing copies use a bounded private cache of up to 512 MiB with seven-day expiry. A separate 32-MiB RAM bitmap cache speeds reopening. Decrypted `unlocked-*` views bypass persistent and cross-document caching. Annotations may appear shortly after the first page.
- Visible handwritten signatures using a finger; **not cryptographic signatures**.
- Unlock password-protected PDFs for reading without storing the password or replacing the encrypted original copy. Temporary decrypted views are deleted on close and cleaned up after a process interruption on the next launch. Author permissions are respected for text access and printing; editing unlocked files currently requires the owner password.

### Privacy and limitations

- Original documents are never modified. There are no accounts, advertising or telemetry. Internet and network-state permissions are used for the startup GitHub release check; GitHub receives a normal HTTPS request (including your IP address), but no documents, filenames, reading progress or device identifiers. PDFs are processed locally. Cloud file providers may need their own network access during import. Downloads and the optional support link open externally in a browser; no PDFs are attached or shared with them.
- **Not full PdfCraft/Acrobat parity:** OCR, reliable editing of existing PDF text, Office conversion, safe redaction, cryptographic signatures, layers/attachments, PDF/A/preflight tools, XFA, full comment threads and other advanced features remain unimplemented. Night rendering also inverts images.
- Tools rewrite the private library copy rather than making incremental PDF updates, which can invalidate existing digital signatures. Edits remove the previous encryption unless password protection is explicitly applied. There is no edit history or undo; keep an external backup of important PDFs.
- Extraction and page deletion do not repair every outline or form reference to removed pages and are **not safe redaction**. Newly protected copies allow reading/printing but restrict text extraction/editing. Merging uses imported library documents; splitting creates private library copies.

See [FEATURES_TODO.md](FEATURES_TODO.md) for remaining features and [TESTING.md](TESTING.md) for validation details. These supporting documents are currently in German.

## Build

Use Android Studio, JDK 17 or newer, Android SDK 36 and Build Tools 36.0.0. Minimum supported device version: **Android 8.0 (API 26)**.

Open the project in Android Studio and point `local.properties` to your SDK installation; do not commit it. Then run:

```sh
./gradlew assembleDebug
./gradlew testDebugUnitTest lintDebug
./gradlew connectedDebugAndroidTest
```

On Windows, use `gradlew.bat`. APK output: `app/build/outputs/apk/debug/app-debug.apk`.

## Architecture

Kotlin, native Android views and AndroidX Activity. Android's `PdfRenderer` renders in the background; PDFBox Android handles text and PDF copies locally. No WebView or backend server. Bounded bitmap caches and on-demand rendering control memory use. Maximum zoom is 5×; PDF import is limited to 500 MiB. Rendering resolution is bounded at extreme zoom; tiled rendering is not implemented yet.

- `MainActivity.kt`: library, Android file selection and reader integration.
- `PdfReaderView.kt`: rendering, layouts, gestures and reading position.
- `PdfViewRotation.kt`: read-only viewing rotation, render transforms and inverse text hit-testing.
- `DocumentStore.kt`: private imports, deduplication and metadata.
- `LibraryFolders.kt`, `FolderDialogs.kt`: virtual folder rules and destination browsing.
- `LibraryBackup.kt`, `BackupEnvelope.kt`, `LibraryBackupUi.kt`: validated archives, optional encryption, atomic library restore and Android file-picker integration.
- `ThumbnailLoader.kt`: background thumbnails with RAM/disk caching.
- `ReadingProgress.kt`: independently tested reading-position normalization.
- `LibraryView.kt`: searchable cover library.
- `PdfTextService.kt`, `ReflowView.kt`: extraction, search, outlines and reflow.
- `PdfTools.kt`, `PdfMarkup.kt`: page organization, forms, notes, markup and visible signatures.
- `ScanActivity.kt`, `ScanCropView.kt`, `ScanPageStrip.kt`, `ScanProcessing.kt`: scanner workflow, corner editing, page strip, perspective correction and image-PDF export.
- `SupportDialog.kt`: optional support message and external browser link.
- `UpdateCheck.kt`, `AppUpdates.kt`, `UpdateNoticeView.kt`: daily release-check policy, GitHub access and inline library update notice.

Bookmarks are app metadata, not changes within the PDF. Documents and reading progress are excluded from automatic Android backup; uninstalling deletes the private library. Export a manual `.pdfpauli` backup to storage outside the app before uninstalling or changing devices.

## Tests

JUnit tests cover progress values, page selection, text processing, spread grouping and curl geometry. Android integration tests cover real import, deduplication, progress, bookmarks, safe removal, invalid files, rendering, gestures, reflow, search, outlines, PDF tools, markup, signatures, forms, passwords, scanner processing and unchanged originals.

The scripts under `tools/` add UI smoke tests on a running emulator. Run instrumentation tests first to create their PDF fixtures. Set `ADB` to an alternate ADB executable and `ANDROID_SERIAL` to select an emulator. Scanner tests require the emulator's camera app and the gallery script also uses Pillow.

```sh
python3 tools/smoke_test.py --output /tmp/opencode/pdfcraft-smoke
python3 tools/reflow_smoke_test.py
python3 tools/zen_ui_smoke_test.py
python3 tools/search_cancel_smoke_test.py
python3 tools/scanner_smoke_test.py
python3 tools/scanner_gallery_smoke_test.py
```

## License

Original project code is licensed under MIT; see [LICENSE](LICENSE). No source code or brand assets from PdfCraft or MJ PDF are included. AndroidX, PDFBox Android and the Gradle Wrapper use Apache-2.0; see [NOTICE](NOTICE). The PDF rendering engine is part of Android.

Material Symbols are sourced through Iconify and bundled as local vector paths (Google, Apache-2.0). No runtime icon downloads or icon font are needed.
