# PDFCraft Android

[![☕ Buy me some tokens](https://img.shields.io/badge/%E2%98%95%20Buy%20me%20some%20tokens-4F7D53?style=for-the-badge)](https://buy.stripe.com/5kQ00c1yP9PQfDP6vV9bO04?client_reference_id=pdfcraft_android)

[Download PDFCraft.apk](https://github.com/marcushoja/PDFCraft_Android/releases/latest/download/PDFCraft.apk)

Many hours of development and testing have gone into PDFCraft. If you enjoy the app, you are welcome to support the project voluntarily. Your support helps with further development. Thank you!

The green heart in the library and **Buy me some tokens** beneath the author credits open a friendly support message. **Buy me a coffee** in that dialog opens the existing Stripe support page in your browser only when you choose to continue. Support is optional and does not unlock features or affect access to your PDFs.

## Download the APK

Get the latest installable APK from [GitHub Releases](https://github.com/marcushoja/PDFCraft_Android/releases/latest): [PDFCraft.apk](https://github.com/marcushoja/PDFCraft_Android/releases/latest/download/PDFCraft.apk).

Every push to `main` automatically builds and checks the app and publishes a release with its SHA-256 checksum. These are **developer-signed preview builds**, not Play Store releases. GitHub builds use the fixed project signing key from the `PDFCRAFT_SIGNING_BUNDLE` secret; its certificate is checked before publication. Releases from build 11 onward support normal updates without uninstalling. Older releases may require a one-time reinstall: save important documents first. Local builds without the release signing environment use a different debug key and cannot update GitHub builds directly. See [SIGNING.md](SIGNING.md).

## Features

### Library and reading

- Private local library with cover thumbnails, reading progress and resume actions.
- Icon buttons switch between grid and compact list views and sort the library by name or date added, ascending or descending. List rows use small covers with a separate options button. View and sorting preferences are remembered.
- Import PDFs through Android's file picker, **Open with** and **Share**. Originals remain unchanged.
- Save each document's page, relative vertical position and zoom. SHA-256 deduplication preserves progress when the same file is imported again.
- Five reading layouts: continuous vertical scrolling, single pages, side-by-side spreads, books with a separate cover, and gapless comic/webtoon scrolling. Independent right-to-left navigation for manga; text and page images are not mirrored. Mixed page sizes, unpaired final pages and saved positions are preserved when changing layouts or viewport size.
- Finger-controlled page curling with a nonlinear cylindrical bitmap mesh, visible paper backside, faint mirrored ink, crease and shadows. Release commits the turn or rolls it back. The mesh uses the hardware-accelerated Canvas path when available, not a dedicated OpenGL renderer. Curl animation currently applies to unzoomed single pages; spreads change without curl.
- Faster page overview with a bounded viewport, cancelable thumbnail requests, reused native PDF renderers, RAM/disk caches and no full annotation inspection per thumbnail. It opens at the current reading position.
- Covers fill their tiles without stretching, using centered cropping and rounded image corners. The options button stays transparent over the cover.
- Renaming starts with the existing name as editable, selected text. Sharing exposes the original or library-chosen name rather than the internal file identifier, without copying large PDFs again.
- **Delete local copy** removes the private PDF, reading progress and bookmarks after confirmation. The original file is kept.
- Pinch zoom, double-tap zoom, fit width or fit the entire page; page overview, direct page jump and app bookmarks.
- True fullscreen including system bars, with an exit control; dark interface, inverted night rendering and optional keep-screen-awake behavior.
- **Text mode / Reflow:** selectable, rewrapped embedded PDF text with font-size and serif options, page navigation and a separately saved reading position. Image-only scans show an explanation rather than fake reflow.
- Cancelable, case-insensitive full-document search with progress and results; search highlights in reflow; expandable, searchable table of contents; sharing, screenshots and Android printing.
- Cancelable progress screens for import, large-document preparation and first-page rendering.
- German, English, French and Spanish under **Settings → Language**. German is the default; **System language** follows the device. PDF contents, filenames and notes are never translated.
- Searchable **Recent**, **Library** and **Bookmarks** views. The interface is inspired by [MJ PDF](https://play.google.com/store/apps/details?id=com.gitlab.mudlej.MjPdfReader); no MJ PDF code is used.

### Camera and image scanner

- Open **Scan PDF** using the library's camera icon. Take photos through the device's camera app or choose existing images with Android's file picker.
- Drag four document corners for perspective correction. A circular 3× magnifier with crosshair stays at the top, opposite the active corner's side.
- Side controls provide brightness/contrast presets, **Grayscale** and **Black/white** with preview. The two monochrome modes are mutually exclusive; tapping the active one again restores color.
- **✓ Apply** saves the current page without opening another capture or picker, then becomes **✓ Continue**: tap it to reopen the last-used camera or image picker. The upper camera/image icons let you change the source. **Export** also applies an unfinished edit before creating the PDF.
- Pinch to zoom and drag to pan the confirmed scan preview without re-entering corner editing. Use **Adjust** explicitly to edit the corners again.
- The labeled action bar provides **Rotate · Adjust · Delete · ✓ Apply · Export**. Adjust is disabled and dimmed while the corners are already being edited; unavailable overflow menus are also dimmed.
- The introductory text embeds the same local icons used by the toolbar and includes readable screen-reader descriptions. There is no automatic-next-photo checkbox or duplicate capture controls below.
- Collected pages appear in a horizontal strip. Hold and drag to reorder; a six-dot indicator beside the page number makes this discoverable. The smaller **X** retains a usable touch target and asks for confirmation before removal. Pages can also be rotated or edited again.
- Drafts are saved privately and can be resumed after an app restart. Draft photos are removed only after successful library import; failed or canceled imports keep the draft available.
- Up to 100 pages, with processed image edges limited to 2200 pixels. PDF output uses A4 pages without stretching, adding white margins for other aspect ratios.
- Processing and export are local, with no account or cloud OCR. Perspective correction targets flat documents, not curved book pages. Without OCR, the exported PDF contains images, not searchable text.

### PDF tools

- Rotate, extract/reorder, delete, merge and split pages; add/read notes, mark text, fill standard forms, edit metadata and apply password protection.
- Icon-grid tool menus. Markup offers six colors and highlight, underline and strikeout, with preview and remembered choices. Reflow selection includes **Mark text**. Query-based marking matches text on the current page; scans require OCR.
- Direct selection on rendered pages: long press, move selection handles, copy, select all or mark. Markup uses only the selected glyphs, including multiline selections, instead of marking every identical word. Supports crop offsets, 0°/90°/180°/270° page rotations, zoom and scrolling. Selection remains on one page; individually rotated/skewed or vertical glyphs are skipped.
- Image-heavy PDFs display natively first, without waiting for complete annotation inspection. Page sizes and prepared viewing copies use a bounded private cache of up to 512 MiB with seven-day expiry. A separate 32-MiB RAM bitmap cache speeds reopening. Decrypted `unlocked-*` views bypass persistent and cross-document caching. Annotations may appear shortly after the first page.
- Visible handwritten signatures using a finger; **not cryptographic signatures**.
- Unlock password-protected PDFs for reading without storing the password or replacing the encrypted original copy. Temporary decrypted views are deleted on close and cleaned up after a process interruption on the next launch. Author permissions are respected for text access and printing; editing unlocked files currently requires the owner password.

### Privacy and limitations

- Original documents are never modified. The app has no Internet permission, accounts, advertising or telemetry. Cloud file providers may need their own network access during import. The optional support link opens externally in a browser; no PDFs are attached or shared with it.
- **Not full PdfCraft/Acrobat parity:** OCR, reliable editing of existing PDF text, Office conversion, safe redaction, cryptographic signatures, layers/attachments, PDF/A/preflight tools, XFA, full comment threads and other advanced features remain unimplemented. Night rendering also inverts images.
- Tools create new copies rather than incremental updates, which can invalidate existing digital signatures. Edited copies are saved without the original encryption unless password protection is explicitly applied.
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
- `DocumentStore.kt`: private imports, deduplication and metadata.
- `ThumbnailLoader.kt`: background thumbnails with RAM/disk caching.
- `ReadingProgress.kt`: independently tested reading-position normalization.
- `LibraryView.kt`: searchable cover library.
- `PdfTextService.kt`, `ReflowView.kt`: extraction, search, outlines and reflow.
- `PdfTools.kt`, `PdfMarkup.kt`: page organization, forms, notes, markup and visible signatures.
- `ScanActivity.kt`, `ScanCropView.kt`, `ScanPageStrip.kt`, `ScanProcessing.kt`: scanner workflow, corner editing, page strip, perspective correction and image-PDF export.
- `SupportDialog.kt`: optional support message and external browser link.

Bookmarks are app metadata, not changes within the PDF. Documents and reading progress are excluded from Android backup; uninstalling deletes the private library.

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
