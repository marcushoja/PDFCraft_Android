"""Android image chooser, five scanner icons, unchanged original and PDF export."""
import hashlib,subprocess,time,re
from pathlib import Path
from PIL import Image,ImageDraw
from smoke_test import adb,tap,hierarchy,wait_hierarchy,screenshot,records
from scanner_smoke_test import ready,draft,drag_corner

def main():
    package='de.pdfcraft.android'
    fixture=Path('/tmp/opencode/pdfcraft-gallery-test.png')
    image=Image.new('RGB',(600,800),'#889988');draw=ImageDraw.Draw(image)
    draw.polygon([(70,80),(530,125),(490,720),(100,680)],fill='white',outline='black',width=5)
    for y in range(180,620,40):draw.line((150,y,440,y),fill='black',width=8)
    image.save(fixture)
    remote='/sdcard/Download/pdfcraft-gallery-test.png'
    adb('push',subprocess.check_output(['wslpath','-w',str(fixture)],text=True).strip(),remote)
    original=hashlib.sha256(adb('exec-out','cat',remote,binary=True)).hexdigest()
    adb('shell','am','force-stop',package)
    adb('shell','am','start','-W','-n',package+'/.MainActivity','-a','android.intent.action.MAIN')
    tap(description='PDF scannen');ready()
    if any(n.get('resource-id')==package+':id/scan_accept_button' for n in hierarchy().iter('node')):
        tap(description='Zurück');ready()
    count_before=len(draft()[1]['pages'])
    assert not any(n.get('resource-id')==package+':id/scan_auto_next' for n in hierarchy().iter('node'))
    tap(description='Bild auswählen')
    tap(description='Show roots')
    root=[n for n in hierarchy().iter('node') if n.get('text')=='Downloads'][-1]
    x1,y1,x2,y2=map(int,re.findall(r'\d+',root.get('bounds')))
    adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))
    wait_hierarchy(lambda ns:any(n.get('content-desc','').startswith('pdfcraft-gallery-test.png,') for n in ns))
    node=next(n for n in hierarchy().iter('node') if n.get('content-desc','').startswith('pdfcraft-gallery-test.png,'))
    tap(description=node.get('content-desc'));ready()
    assert draft()[1]['from_camera']==False
    assert all(any(n.get('text')==label for n in hierarchy().iter('node')) for label in ['Drehen','Anpassen','Löschen','Weiter','Exportieren'])
    drag_corner()
    tap(description='Seite drehen');ready()
    out=Path('/tmp/opencode/pdfcraft-scanner');out.mkdir(exist_ok=True)
    screenshot(out,'gallery-icons.png')
    tap(description='Weiter')
    wait_hierarchy(lambda ns:any(n.get('content-desc')=='Show roots' for n in ns))
    adb('shell','input','keyevent','4');ready()
    count=len(draft()[1]['pages']);assert count==count_before+1
    assert not draft()[1].get('pending')
    assert hashlib.sha256(adb('exec-out','cat',remote,binary=True)).hexdigest()==original
    name='Bilder-Test-'+str(time.time_ns())
    tap(description='Seitenränder bearbeiten');ready()
    tap(description='Exportieren');adb('shell','input','text',name);tap(text='PDF erstellen')
    wait_hierarchy(lambda ns:any(n.get('resource-id')==package+':id/page_indicator' and n.get('text','').endswith(f' / {count}') for n in ns))
    assert next(d for d in records() if d['name']==name+'.pdf')['pageCount']==count
    screenshot(out,'gallery-pdf.png')
    adb('shell','rm',remote)
    print('PASS: image picker, top loupe, simplified editor, Next reopens image selection, unchanged source and Export commits current edit')

if __name__=='__main__':main()
