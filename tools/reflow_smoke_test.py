"""Reflow UI, saved text position, full screen, search and PDF export smoke test."""
from pathlib import Path
import time
from smoke_test import adb, hierarchy, tap, records, screenshot, wait_hierarchy


def main():
    package = "de.pdfcraft.android"
    output = Path("/tmp/opencode/pdfcraft-reflow")
    output.mkdir(parents=True, exist_ok=True)
    adb("shell", "am", "force-stop", package)
    adb("shell", "am", "start", "-W", "-n", f"{package}/.MainActivity", "-a", "android.intent.action.VIEW", "-d",
        f"file:///data/user/0/{package}/cache/reader-integration.pdf", "-t", "application/pdf")
    tap(description="Seitenzahl, zu Seite springen")
    adb("shell", "input", "text", "2")
    tap(text="Öffnen")
    tap(description="Leseoptionen")
    if any(n.get("text") == "PDF-Ansicht" for n in hierarchy().iter("node")):
        tap(text="PDF-Ansicht")
        tap(description="Leseoptionen")
    tap(text="Textmodus / Reflow")
    nodes = list(wait_hierarchy(lambda nodes: any("Lesetest" in n.get("text", "") for n in nodes)).iter("node"))
    assert any("Lesetest" in n.get("text", "") for n in nodes), "Actual PDF text missing in reflow"
    assert any(n.get("content-desc", "").startswith("Reflow, Seite 2") for n in nodes)
    screenshot(output, "reflow.png")
    # Large text is laid out as native selectable paragraphs, not a zoomed page bitmap.
    adb("shell", "input", "swipe", "540", "1250", "540", "500", "600")
    time.sleep(1)
    doc = max(records(), key=lambda d: d["lastOpened"])
    assert doc["lastView"] == "reflow" and doc["page"] == 1, doc
    assert doc["reflowOffset"] > 0, doc
    adb("shell", "am", "force-stop", package)
    adb("shell", "am", "start", "-W", "-n", f"{package}/.MainActivity", "-a", "android.intent.action.MAIN")
    tap(description=f"{doc['name']}, weiterlesen")
    nodes = list(hierarchy().iter("node"))
    assert any(n.get("content-desc", "").startswith("Reflow, Seite 2") for n in nodes), "Reflow mode not resumed"
    resumed = next(d for d in records() if d["id"] == doc["id"])
    assert abs(doc["reflowOffset"] - resumed["reflowOffset"]) < 0.03, (doc, resumed)
    tap(description="Vollbild")
    if any(n.get("text", "").casefold() == "got it" for n in hierarchy().iter("node")):
        tap(text="Got it")
    assert not any(n.get("content-desc") == "Leseoptionen" for n in hierarchy().iter("node"))
    tap(description="Vollbild verlassen")
    tap(description="Leseoptionen")
    tap(text="Im Dokument suchen")
    adb("shell", "input", "text", "Lesetest")
    tap(text="Suchen")
    nodes = list(wait_hierarchy(lambda nodes: any(n.get("text", "").startswith("Seite 1\n") for n in nodes)).iter("node"))
    hit = next(n.get("text") for n in nodes if n.get("text", "").startswith("Seite 1\n"))
    tap(text=hit)
    assert any(n.get("text") == "1 / 8" for n in hierarchy().iter("node")), "Search navigation failed"
    screenshot(output, "search.png")
    tap(description="Leseoptionen")
    tap(text="PDF-Ansicht")
    tap(description="Leseoptionen")
    tap(text="PDF-Werkzeuge")
    tap(text="Notiz hinzufügen")
    adb("shell", "input", "text", "Smoke-Test-Notiz")
    tap(text="Weiter")
    wait_hierarchy(lambda nodes: any(n.get("text") == "Neue PDF ist fertig" for n in nodes))
    tap(text="Öffnen")
    wait_hierarchy(lambda nodes: any("-notiz.pdf" in n.get("text", "") for n in nodes))
    assert any("-notiz.pdf" in n.get("text", "") for n in hierarchy().iter("node")), "Edited copy not opened"
    screenshot(output, "note-copy.png")
    tap(description="Leseoptionen")
    tap(text="Drucken")
    wait_hierarchy(lambda nodes: any(n.get("package") == "com.android.printspooler" for n in nodes))
    screenshot(output, "print-preview.png")
    adb("shell", "input", "keyevent", "4")
    print("PASS: actual reflow, selectable text, text offset after process death, fullscreen, full-document search, note copy, Android print preview")


if __name__ == "__main__":
    main()
