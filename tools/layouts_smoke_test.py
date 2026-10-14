"""Book/spread/webtoon settings, persisted RTL and native logical-page navigation."""
from pathlib import Path
import re,time
from smoke_test import adb,tap,check,hierarchy,wait_hierarchy,screenshot,select_reading_mode


def settings(layout,rtl=False):
    tap(description='Leseoptionen');tap(description='Einstellungen')
    select_reading_mode(layout)
    check('Leserichtung rechts nach links · Manga',rtl)
    check('Dunkle Oberfläche',False);check('PDF-Farben für die Nacht invertieren',False)
    tap(text='Übernehmen')


def jump(number):
    tap(description='Seitenzahl, zu Seite springen')
    adb('shell','input','text',str(number));tap(text='Öffnen')


def indicator(value):
    wait_hierarchy(lambda ns:any(n.get('text')==value for n in ns))


def main():
    p='de.pdfcraft.android';out=Path('/tmp/opencode/pdfcraft-layouts');out.mkdir(parents=True,exist_ok=True)
    adb('shell','am','force-stop',p)
    adb('shell','am','start','-W','-n',p+'/.MainActivity','-a','android.intent.action.VIEW','-d',f'file:///data/user/0/{p}/cache/reader-integration.pdf','-t','application/pdf')
    tap(description='Leseoptionen')
    if any(n.get('text')=='PDF-Ansicht' for n in hierarchy().iter('node')):tap(text='PDF-Ansicht')
    else:adb('shell','input','keyevent','4')
    settings('Zwei Seiten · nebeneinander');jump(1);indicator('1–2 / 8')
    screenshot(out,'spread.png');tap(description='Nächste Seite');indicator('3–4 / 8')
    settings('Buch · separates Deckblatt');jump(1);indicator('1 / 8')
    tap(description='Nächste Seite');indicator('2–3 / 8');screenshot(out,'book.png')
    jump(8);indicator('8 / 8');tap(description='Vorherige Seite');indicator('6–7 / 8')
    settings('Buch · separates Deckblatt',True);jump(1)
    node=next(n for n in hierarchy().iter('node') if n.get('resource-id')==p+':id/pdf_canvas')
    x1,y1,x2,y2=map(int,re.findall(r'\d+',node.get('bounds')))
    adb('shell','input','touchscreen','-d','0','swipe',str(x1+(x2-x1)//5),str((y1+y2)//2),str(x1+(x2-x1)*4//5),str((y1+y2)//2),'500')
    indicator('2–3 / 8');screenshot(out,'rtl-book.png')
    tap(description='Zur Bibliothek')
    adb('shell','am','force-stop',p);adb('shell','am','start','-W','-n',p+'/.MainActivity','-a','android.intent.action.MAIN')
    tap(description='reader-integration.pdf, weiterlesen');indicator('2–3 / 8')
    settings('Comic / Webtoon · lückenlos vertikal',False);jump(2);indicator('2 / 8');screenshot(out,'comic.png')
    settings('Einzelseiten · horizontal wischen',False)
    print('PASS: two-page spreads, separate book cover, unpaired final page, RTL swipe and persisted layout, gapless comic preset')


if __name__=='__main__':main()
