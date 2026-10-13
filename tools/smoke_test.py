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
    serial = os.environ.get("ANDROID_SERIAL")
    selector = ["-s", serial] if serial else []
    return subprocess.check_output([ADB, *selector, *args], text=not binary)


def hierarchy():
    adb("shell", "uiautomator", "dump", "/sdcard/pdfcraft-smoke.xml")
    return ET.fromstring(adb("shell", "cat", "/sdcard/pdfcraft-smoke.xml"))


def wait_hierarchy(predicate, timeout=30):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        tree = hierarchy()
        if predicate(list(tree.iter("node"))):
            return tree
        time.sleep(0.2)
    raise AssertionError("UI did not reach the expected state")


def tap(text=None, description=None):
    tree = None
    if description != "Laden abbrechen":
        tree = wait_hierarchy(lambda nodes: not any(n.get("resource-id") == "de.pdfcraft.android:id/pdf_loading" for n in nodes))
    if tree is None:
        tree = hierarchy()
    for node in tree.iter("node"):
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
    data = adb("exec-out", "screencap", "-p", binary=True)
    # Recent Android builds may prepend a multiple-display warning to stdout.
    start = data.find(b"\x89PNG\r\n\x1a\n")
    if start < 0:
        raise AssertionError("Screenshot is not a PNG")
    (directory / name).write_bytes(data[start:])


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
    if any(n.get("text") == "PDF-Ansicht" for n in hierarchy().iter("node")):
        tap(text="PDF-Ansicht")
        tap(description="Leseoptionen")
    tap(description="Einstellungen")
    check("Fortlaufend · vertikal scrollen")
    check("Ganze Seite einpassen", False)
    tap(text="Übernehmen")
    tap(description="Leseoptionen")
    tap(text="Zoom zurücksetzen")
    tap(description="Seitenzahl, zu Seite springen")
    adb("shell", "input", "text", "5")
    tap(text="Öffnen")
    if not any(n.get("resource-id") == "de.pdfcraft.android:id/bookmark_button" and n.get("selected") == "true" for n in hierarchy().iter("node")):
        tap(description="Lesezeichen setzen oder entfernen")
    time.sleep(0.6)  # Allow debounced progress persistence.
    doc = max((d for d in records() if d["name"] == "reader-integration.pdf"), key=lambda d: d["lastOpened"])
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
    assert any(n.get("resource-id") == "de.pdfcraft.android:id/bookmark_button" and n.get("selected") == "true" for n in nodes), "Bookmark not restored"
    resumed = next(d for d in records() if d["id"] == doc["id"])
    assert abs(resumed["offset"] - doc["offset"]) < 0.02, (doc, resumed)
    assert abs(resumed["zoom"] - doc["zoom"]) < 0.01, (doc, resumed)
    tap(description="Seiten")
    screenshot(output, "pages.png")
    tap(text="Schließen")
    # A single tap toggles distraction-free mode, another restores controls.
    tap(description="PDF-Dokument")
    if any(n.get("text", "").casefold() == "got it" for n in hierarchy().iter("node")):
        tap(text="Got it")
    assert not any(n.get("content-desc") == "Leseoptionen" for n in hierarchy().iter("node"))
    tap(description="PDF-Dokument")
    tap(description="Leseoptionen")
    tap(description="Einstellungen")
    check("Einzelseiten · horizontal wischen")
    check("Ganze Seite einpassen")
    check("Dunkle Oberfläche")
    check("PDF-Farben für die Nacht invertieren")
    tap(text="Übernehmen")
    screenshot(output, "night.png")
    tap(description="Nächste Seite")
    assert any(n.get("text") == "6 / 8" for n in hierarchy().iter("node")), "Single-page navigation failed"
    pdf_node = next(n for n in hierarchy().iter("node") if n.get("content-desc", "").startswith("PDF-Dokument"))
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", pdf_node.get("bounds")))
    adb("shell", "input", "touchscreen", "-d", "0", "swipe", str(x1 + (x2-x1)*4//5), str((y1+y2)//2),
        str(x1 + (x2-x1)//5), str((y1+y2)//2), "500")
    screenshot(output, "page-turn.png")
    wait_hierarchy(lambda nodes: any(n.get("text") == "7 / 8" for n in nodes))
    print("PASS: import, navigation, bookmark, scroll, process restart, exact resume, thumbnails, read mode, night mode, animated one-page swipe")


if __name__ == "__main__":
    main()
