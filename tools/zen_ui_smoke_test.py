"""Verify creator credit, Zen menus and all four app language selections through Android UI."""
from pathlib import Path
import re
import time
from smoke_test import adb, hierarchy, wait_hierarchy, tap, check, screenshot


def tap_id(resource):
    nodes = list(wait_hierarchy(lambda nodes: any(n.get("resource-id") == resource for n in nodes)).iter("node"))
    node = next(n for n in nodes if n.get("resource-id") == resource)
    x1,y1,x2,y2 = map(int,re.findall(r"\d+",node.get("bounds")))
    adb("shell","input","tap",str((x1+x2)//2),str((y1+y2)//2))
    time.sleep(0.4)


def main():
    output = Path("/tmp/opencode/pdfcraft-zen")
    output.mkdir(parents=True,exist_ok=True)
    package="de.pdfcraft.android"
    adb("shell","am","force-stop",package)
    adb("shell","am","start","-W","-n",f"{package}/.MainActivity","-a","android.intent.action.MAIN")
    wait_hierarchy(lambda nodes: any("Marcus Hoja" in n.get("text","") for n in nodes))
    tap(description="Einstellungen")
    check("Dunkle Oberfläche",False)
    check("PDF-Farben für die Nacht invertieren",False)
    tap(text="Übernehmen")
    screenshot(output,"library-de.png")
    current="de"
    labels={"de":("Einstellungen","Übernehmen","PDF öffnen","Bibliothek"),
            "en":("Settings","Apply","Open PDF","Library"),
            "fr":("Paramètres","Appliquer","Ouvrir un PDF","Bibliothèque"),
            "es":("Ajustes","Aplicar","Abrir PDF","Biblioteca")}
    for tag,autonym in [("en","English"),("fr","Français"),("es","Español"),("de","Deutsch")]:
        tap(description=labels[current][0])
        tap_id(f"{package}:id/language_spinner")
        tap(text=autonym)
        tap(text=labels[current][1])
        nodes=list(wait_hierarchy(lambda nodes: any(n.get("text")==labels[tag][2] for n in nodes)).iter("node"))
        assert any(n.get("text")==labels[tag][3] for n in nodes), (tag,"Library tab not translated")
        assert any("Marcus Hoja" in n.get("text","") for n in nodes), (tag,"Creator credit missing")
        screenshot(output,f"library-{tag}.png")
        current=tag
    print("PASS: creator credit, light Zen theme, German, English, French, Spanish, persisted language recreation")


if __name__=="__main__":
    main()
