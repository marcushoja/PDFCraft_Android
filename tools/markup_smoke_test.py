"""Exercise the icon tool grid, color picker and real saved PDF markup copy."""
from pathlib import Path
import time
from smoke_test import adb,tap,wait_hierarchy,screenshot,records,wait_local_edit

def main():
    out=Path('/tmp/opencode/pdfcraft-markup');out.mkdir(parents=True,exist_ok=True)
    package='de.pdfcraft.android'
    adb('shell','am','force-stop',package)
    adb('shell','am','start','-W','-n',package+'/.MainActivity','-a','android.intent.action.VIEW','-d','file:///data/user/0/'+package+'/cache/reader-integration.pdf','-t','application/pdf')
    wait_hierarchy(lambda ns:any(n.get('resource-id')==package+':id/menu_button' for n in ns))
    tap(description='Leseoptionen');tap(text='PDF-Werkzeuge')
    screenshot(out,'tools.png')
    tap(description='Text markieren')
    tap(description='Blau');tap(description='Unterstreichen')
    screenshot(out,'palette.png')
    adb('shell','input','text','PDFCraft')
    time.sleep(1)
    if 'mInputShown=true' in adb('shell','dumpsys','input_method'):
        adb('shell','input','keyevent','4')
        time.sleep(0.5)
    before = max(records(), key=lambda d: d['lastOpened'])
    count = len(records())
    tap(text='Änderungen speichern')
    wait_local_edit(before)
    assert len(records()) == count, 'Markup created an extra library copy'
    screenshot(out,'blue-underline.png')
    tap(description='Leseoptionen');tap(text='Drucken')
    wait_hierarchy(lambda ns:any(n.get('package')=='com.android.printspooler' for n in ns))
    screenshot(out,'annotated-print.png')
    adb('shell','input','keyevent','4')
    print('PASS: icon PDF tools, blue underline palette, current local PDF updated without another copy')

if __name__=='__main__':main()
