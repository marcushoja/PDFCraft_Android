"""UI smoke test against the installed debug app and integration-test PDF."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

ADB = os.environ.get("ADB", "adb")
PACKAGE = "de.pdfcraft.android"


def adb(*args, binary=False):
    return subprocess.check_output([ADB, *args], text=not binary)


def hierarchy():
    adb("shell", "uiautomator", "dump", "/sdcard/pdfcraft-smoke.xml")
    return ET.fromstring(adb("shell", "cat", "/sdcard/pdfcraft-smoke.xml"))


def tap(text=None, description=None):
    for node in hierarchy().iter("node"):
        text_matches = text is not None and node.get("text", "").casefold() == text.casefold()
        desc_matches = description is not None and node.get("content-desc") == description
        reader_matches = description == "PDF-Dokument" and node.get("content-desc", "").startswith("PDF-Dokument,")
        if text_matches or desc_matches or reader_matches:
            x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
            adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
            time.sleep(0.4)  # Dialog/window animations and single-tap confirmation.
            return
    raise AssertionError(f"UI element missing: {text or description}")


def records():
    xml = adb("shell", "run-as", PACKAGE, "cat", "shared_prefs/pdfcraft_documents.xml")
    return json.loads(ET.fromstring(xml).find("string[@name='documents']").text)


def check(label, checked=True):
    node = next(n for n in hierarchy().iter("node") if n.get("text") == label)
    if (node.get("checked") == "true") != checked:
        tap(text=label)


def screenshot(directory, name):
    (directory / name).write_bytes(adb("exec-out", "screencap", "-p", binary=True))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", default="/tmp/opencode/pdfcraft-smoke")
    args = parser.parse_args()
    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=True)
    adb("shell", "am", "force-stop", PACKAGE)
    adb("shell", "am", "start", "-W", "-n", f"{PACKAGE}/.MainActivity", "-a",
        "android.intent.action.VIEW", "-d",
        f"file:///data/user/0/{PACKAGE}/cache/reader-integration.pdf", "-t", "application/pdf")
    tap(description="Leseoptionen")
    tap(text="Ansicht & Lesekomfort")
    check("Fortlaufend · vertikal scrollen")
    check("Ganze Seite einpassen", False)
    tap(text="Übernehmen")
    tap(description="Seitenzahl, zu Seite springen")
    adb("shell", "input", "text", "5")
    tap(text="Öffnen")
    if not any(n.get("text") == "★" for n in hierarchy().iter("node")):
        tap(description="Lesezeichen setzen oder entfernen")
    time.sleep(0.6)  # Allow debounced progress persistence.
    doc = next(d for d in records() if d["name"] == "reader-integration.pdf")
    assert doc["page"] == 4, doc
    assert 4 in doc["bookmarks"], doc
    screenshot(output, "reader.png")
    # Scroll within this page and check that the exact offset survives restart.
    pdf_node = next(n for n in hierarchy().iter("node") if n.get("content-desc", "").startswith("PDF-Dokument"))
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", pdf_node.get("bounds")))
    cx, cy = (x1 + x2) // 2, (y1 + y2) // 2
    adb("shell", "input", "swipe", str(cx), str(cy + 100), str(cx), str(cy - 180), "500")
    time.sleep(0.8)
    doc = next(d for d in records() if d["id"] == doc["id"])
    assert doc["page"] == 4 and doc["offset"] > 0, doc
    # Simulate process death: no onPause/onSaveInstanceState assistance.
    adb("shell", "am", "force-stop", PACKAGE)
    adb("shell", "am", "start", "-W", "-n", f"{PACKAGE}/.MainActivity", "-a", "android.intent.action.MAIN")
    screenshot(output, "library.png")
    tap(description="reader-integration.pdf, weiterlesen")
    nodes = list(hierarchy().iter("node"))
    assert any(n.get("text") == "5 / 8" for n in nodes), "Reading position not restored"
    assert any(n.get("text") == "★" for n in nodes), "Bookmark not restored"
    resumed = next(d for d in records() if d["id"] == doc["id"])
    assert abs(resumed["offset"] - doc["offset"]) < 0.02, (doc, resumed)
    assert abs(resumed["zoom"] - doc["zoom"]) < 0.01, (doc, resumed)
    tap(text="Seiten")
    screenshot(output, "pages.png")
    tap(text="Schließen")
    # A single tap toggles distraction-free mode, another restores controls.
    tap(description="PDF-Dokument")
    assert not any(n.get("content-desc") == "Leseoptionen" for n in hierarchy().iter("node"))
    tap(description="PDF-Dokument")
    tap(description="Leseoptionen")
    tap(text="Ansicht & Lesekomfort")
    check("Einzelseiten · horizontal wischen")
    check("Ganze Seite einpassen")
    check("Dunkle Oberfläche")
    check("PDF-Farben für die Nacht invertieren")
    tap(text="Übernehmen")
    screenshot(output, "night.png")
    tap(description="Nächste Seite")
    assert any(n.get("text") == "6 / 8" for n in hierarchy().iter("node")), "Single-page navigation failed"
    print("PASS: import, navigation, bookmark, scroll, process restart, exact resume, thumbnails, read mode, night mode")


if __name__ == "__main__":
    main()
