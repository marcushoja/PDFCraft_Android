# PDFPauli library backup · version 1

Manual backups use the `.pdfpauli` extension and MIME type `application/octet-stream`.
They contain private library PDFs, logical folders, reading metadata and explicitly
allowlisted reader/library settings. They do not contain passwords, scanner drafts,
update state, caches, original source URIs or temporary decrypted viewing copies.

## Envelope

All integers in the envelope are unsigned big-endian values.

- Eight ASCII bytes: `PPAULI01`.
- One mode byte: `0` for an unencrypted ZIP payload, `1` for encrypted records.
- Mode 0: the remaining bytes are the ZIP archive.
- Mode 1: a random 16-byte salt and random 8-byte nonce prefix, then records.

Mode 1 derives a 256-bit AES key using PBKDF2-HMAC-SHA256 with 600,000 iterations.
Passwords are passed to Java/Android `PBEKeySpec` without normalization. Each record
uses AES/GCM/NoPadding with a 128-bit authentication tag. The 12-byte nonce is the
8-byte random prefix followed by the 4-byte record index, starting at zero. Associated
data is the magic, mode byte, salt and nonce prefix concatenated in that order.

Each record is a 4-byte ciphertext length followed by ciphertext including the tag.
Payloads are at most 65,536 bytes (ciphertext at most 65,552 bytes). An authenticated
empty record (16 bytes of ciphertext/tag) terminates the stream and must be followed
by EOF. Missing, reordered, changed or appended records are rejected. Processing is
bounded-memory, including on Android providers that buffer an individual GCM record.
This is an application-specific container using standard cryptographic primitives,
not a standard encrypted ZIP format.

## ZIP payload

Only these entries are allowed, with no duplicates or directory entries:

- `manifest.json`: UTF-8 JSON, at most 8 MiB.
- `documents/<document-id>.pdf`: one original private PDF copy per document.

Manifest fields:

- `format`: `PDFPauli`; `version`: integer `1`; `createdAt`: Unix milliseconds.
- `documents`: document metadata, including ID, name, internal filename, dates,
  page count, page/offset/zoom, bookmarks, reflow position/mode, view rotation,
  current content hash and optional folder ID. IDs and filenames are validated;
  document IDs consist of 64 lowercase hexadecimal characters.
- `folders`: UUID, user-authored name and nullable parent UUID. Parent references,
  sibling-name uniqueness, depth and absence of cycles are validated.
- `hashes`: document ID to SHA-256 of the actual PDF bytes.
- `settings`: `reader_settings` and `library_settings` objects. Only supported
  keys, types and ranges are accepted, never arbitrary preference files or paths.

Limits are 10,000 PDFs, 10,000 folders, 64 folder levels, 500 MiB per PDF and 32 GiB
of PDF payload. A collection restore adds one folder level, so an already 64-level
backup must use replacement or have its folder depth reduced before export.
Unencrypted checksums detect corruption; they do not authenticate the backup against
intentional alteration. Use password protection when authentication is required.

## Restore transaction and compatibility

The complete archive is authenticated and checked in private temporary storage before
confirmation. Paths are never taken from the archive for filesystem extraction.
Restore installs new files under validated, collision-free IDs; it never overwrites
a currently referenced PDF. One synchronous SharedPreferences commit publishes the
entire document/folder graph. Failure before commit removes newly installed files.
Old files are removed only after a successful replacement commit. A process death
before commit leaves the previous library intact, possibly with unreferenced files;
it does not publish half a library. Private abandoned archive stages are cleaned
after 24 hours on a later launch. Unreferenced library files are not automatically
deleted, to avoid racing imports or local PDF edits.

Collection import remaps document/folder IDs and preserves all existing metadata and
settings. Replacement preserves folder IDs and remaps document IDs only on collision.
Bookmarks and reading metadata remain attached to their restored document. Embedded
annotations remain in unchanged PDF bytes. Replacement settings are journaled in the
same commit and applied idempotently after commit or on restart, before locale setup.

Unknown envelope modes and manifest versions are rejected rather than guessed.
Any future schema migration must explicitly support older versions and have round-trip,
corruption, cancellation and rollback tests. Keep the original backup until successful
restore has been independently checked. Android file providers determine when an
external write is durable; the app reports success only after closing the output stream.
