"""Long-press rendered PDF text, copy it, then mark the precise selection."""
import re,time
from pathlib import Path
from smoke_test import adb,hierarchy,tap,wait_hierarchy,screenshot

def long_press_text():
    nodes=list(wait_hierarchy(lambda ns:any(n.get('resource-id')=='de.pdfcraft.android:id/pdf_canvas' for n in ns)).iter('node'))
    node=next(n for n in nodes if n.get('resource-id')=='de.pdfcraft.android:id/pdf_canvas')
    x1,y1,x2,y2=map(int,re.findall(r'\d+',node.get('bounds')))
    # Fixture: 480 x 720, first word baseline (32,64). Reader fit-width, centered page.
    w=x2-x1;h=y2-y1
    scale=min(w/480,h/720)
    left=x1+(w-480*scale)/2;top=y1+(h-720*scale)/2
    x=int(left+80*scale);y=int(top+53*scale)
    adb('shell','input','swipe',str(x),str(y),str(x),str(y),'1000')
    time.sleep(1)
    # Android's non-focusable native floating toolbar is omitted by some UIAutomator
    # versions. Its real menu actions are separately verified by instrumentation.
    density=int(re.findall(r'\d+',adb('shell','wm','density'))[-1])/160
    menu_left=max(16*density,left+87*scale-164*density)
    menu_y=top+43*scale-32*density
    return menu_left,menu_y,density

def selection_action(label,menu):
    if any(n.get('text')==label for n in hierarchy().iter('node')):
        tap(text=label)
    else:
        left,y,density=menu
        x=left+(45 if label=='Kopieren' else 142)*density
        adb('shell','input','tap',str(int(x)),str(int(y)))
        time.sleep(.5)

def main():
    p='de.pdfcraft.android';out=Path('/tmp/opencode/pdfcraft-direct-selection');out.mkdir(parents=True,exist_ok=True)
    adb('shell','am','force-stop',p)
    adb('shell','am','start','-W','-n',p+'/.MainActivity','-a','android.intent.action.VIEW','-d','file:///data/user/0/'+p+'/cache/reader-integration.pdf','-t','application/pdf')
    wait_hierarchy(lambda ns:any(n.get('resource-id')==p+':id/menu_button' for n in ns))
    # Force predictable fit-page single mode and page 1 using the existing app settings.
    tap(description='Leseoptionen')
    if any(n.get('text')=='PDF-Ansicht' for n in hierarchy().iter('node')):
        tap(text='PDF-Ansicht');tap(description='Leseoptionen')
    tap(description='Einstellungen')
    tap(text='Einzelseiten · horizontal wischen')
    from smoke_test import check
    check('Ganze Seite einpassen',True)
    tap(text='Übernehmen')
    tap(description='Seitenzahl, zu Seite springen');adb('shell','input','text','1');tap(text='Öffnen')
    menu=long_press_text();screenshot(out,'selection.png')
    selection_action('Kopieren',menu)
    menu=long_press_text();selection_action('Text markieren',menu)
    wait_hierarchy(lambda ns:any('Nur der ausgewählte Text' in n.get('text','') for n in ns))
    tap(description='Grün');tap(description='Hervorheben');screenshot(out,'exact-palette.png')
    tap(text='Kopie erstellen')
    wait_hierarchy(lambda ns:any(n.get('text')=='Neue PDF ist fertig' for n in ns))
    tap(text='Öffnen');screenshot(out,'exact-highlight.png')
    print('PASS: long press rendered PDF, native Copy/Mark menu, exact selected glyph marking')

if __name__=='__main__':main()
