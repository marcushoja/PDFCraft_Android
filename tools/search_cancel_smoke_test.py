"""Cancel a running document-wide search, then immediately run a case-insensitive search."""
from pathlib import Path
import time
from smoke_test import adb, hierarchy, wait_hierarchy, tap, screenshot


def main():
    package="de.pdfcraft.android"
    output=Path("/tmp/opencode/pdfcraft-search-cancel")
    output.mkdir(parents=True,exist_ok=True)
    adb("shell","am","force-stop",package)
    adb("shell","am","start","-W","-n",f"{package}/.MainActivity","-a","android.intent.action.VIEW","-d",
        f"file:///data/user/0/{package}/cache/large-reader-test.pdf","-t","application/pdf")
    wait_hierarchy(lambda nodes: not any(n.get("resource-id")==f"{package}:id/pdf_loading" for n in nodes))
    tap(description="Leseoptionen")
    screenshot(output,"actions.png")
    tap(text="Im Dokument suchen")
    adb("shell","input","text","not-present")
    tap(text="Suchen")
    wait_hierarchy(lambda nodes: any(n.get("text")=="Dokument wird durchsucht" for n in nodes))
    screenshot(output,"search-progress.png")
    tap(text="Abbrechen")
    time.sleep(0.8)
    assert not any(n.get("text")=="Dokument wird durchsucht" for n in hierarchy().iter("node"))
    tap(description="Leseoptionen")
    tap(text="Im Dokument suchen")
    adb("shell","input","text","SELECTABLE")
    tap(text="Suchen")
    nodes=list(wait_hierarchy(lambda nodes: any(n.get("text","").startswith("Seite 1\n") for n in nodes)).iter("node"))
    assert any("selectable" in n.get("text","") for n in nodes), "Search did not ignore capitalization"
    screenshot(output,"case-insensitive-results.png")
    tap(text="Schließen")
    print("PASS: running search cancellation, immediate new search, uppercase query matches lowercase PDF text")


if __name__=="__main__":
    main()
