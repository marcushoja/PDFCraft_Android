"""Real emulator camera → corner drag → page strip → resumed draft → library PDF."""
import io,json,re,time,xml.etree.ElementTree as ET
from pathlib import Path
from PIL import Image,ImageOps
from smoke_test import adb,tap,hierarchy,wait_hierarchy,screenshot,records

PACKAGE='de.pdfcraft.android'

def ready():
    return wait_hierarchy(lambda ns:any(n.get('resource-id')==PACKAGE+':id/scan_crop' for n in ns)
        and not any(n.get('text') in ['Bild wird verarbeitet…','PDF wird erstellt…','Abbrechen'] for n in ns))

def draft():
    xml=ET.fromstring(adb('shell','run-as',PACKAGE,'cat','shared_prefs/scanner_draft.xml'))
    token=next(n.text for n in xml if n.get('name')=='session')
    data=json.loads(adb('shell','run-as',PACKAGE,'cat',f'files/scanner-drafts/{token}/session.json'))
    return token,data

def capture(already_open=False):
    if not already_open:tap(description='Foto aufnehmen')
    wait_hierarchy(lambda ns:any(n.get('content-desc')=='Shutter' and n.get('enabled')=='true' for n in ns))
    time.sleep(1)  # Emulator camera session can lag behind its enabled shutter UI.
    tap(description='Shutter')
    wait_hierarchy(lambda ns:any(n.get('content-desc')=='Done' for n in ns))
    tap(description='Done');ready()

def black_white(enabled):
    node=next(n for n in hierarchy().iter('node') if n.get('resource-id')==PACKAGE+':id/scan_black_white')
    if (node.get('selected')=='true')!=enabled:tap(text='Schwarz/Weiß')

def next_camera_then_cancel():
    tap(description='Weiter')
    wait_hierarchy(lambda ns:any(n.get('content-desc')=='Shutter' for n in ns))
    adb('shell','input','keyevent','4');ready()

def drag_thumbnail_last_to_first():
    before=draft()[1]['pages']
    nodes=[n for n in hierarchy().iter('node') if n.get('content-desc','').endswith('Zum Umsortieren lange drücken und ziehen')]
    assert len(nodes)>=2
    def point(node):
        x1,y1,x2,y2=map(int,re.findall(r'\d+',node.get('bounds')))
        return x1+(x2-x1)//4,y1+(y2-y1)*3//4
    x,y=point(nodes[-1]);tx,ty=point(nodes[0])
    adb('shell','input','motionevent','DOWN',str(x),str(y));time.sleep(.8)
    adb('shell','input','motionevent','MOVE',str(tx),str(ty));time.sleep(.3)
    adb('shell','input','motionevent','UP',str(tx),str(ty));ready()
    after=draft()[1]['pages']
    assert after[0]['id']==before[-1]['id'],(before,after)

def drag_corner():
    token,data=draft()
    photo=ImageOps.exif_transpose(Image.open(io.BytesIO(adb('exec-out','run-as',PACKAGE,'cat',
        f'files/scanner-drafts/{token}/{data["crop"]}',binary=True))))
    node=next(n for n in hierarchy().iter('node') if n.get('resource-id')==PACKAGE+':id/scan_crop')
    x1,y1,x2,y2=map(int,re.findall(r'\d+',node.get('bounds')))
    density=int(re.findall(r'\d+',adb('shell','wm','density'))[-1])/160
    scale=min((x2-x1-48*density)/photo.width,(y2-y1-48*density)/photo.height)
    w,h=photo.width*scale,photo.height*scale
    left=x1+(x2-x1-w)/2;top=y1+(y2-y1-h)/2
    adb('shell','input','motionevent','DOWN',str(int(left)),str(int(top)))
    adb('shell','input','motionevent','MOVE',str(int(left+w*.12)),str(int(top+h*.10)))
    time.sleep(.3)
    out=Path('/tmp/opencode/pdfcraft-scanner');out.mkdir(parents=True,exist_ok=True)
    screenshot(out,'camera-loupe.png')
    adb('shell','input','motionevent','UP',str(int(left+w*.12)),str(int(top+h*.10)))
    time.sleep(.3)
    corners=draft()[1]['corners']
    assert corners[0][0]>.08 and corners[0][1]>.06,corners

def main():
    out=Path('/tmp/opencode/pdfcraft-scanner');out.mkdir(parents=True,exist_ok=True)
    adb('shell','am','force-stop',PACKAGE)
    adb('shell','am','start','-W','-n',PACKAGE+'/.MainActivity','-a','android.intent.action.MAIN')
    tap(description='PDF scannen');ready()
    if any(n.get('resource-id')==PACKAGE+':id/scan_accept_button' for n in hierarchy().iter('node')):
        tap(description='Zurück');ready()
    assert not any(n.get('resource-id')==PACKAGE+':id/scan_auto_next' for n in hierarchy().iter('node'))
    print('Scanner: camera and corner magnifier',flush=True)
    capture();tap(text='Heller');black_white(False)
    drag_corner();screenshot(out,'camera-corners.png')
    tap(description='Weiter')
    capture(already_open=True)
    assert draft()[1]['tone']=='BRIGHTER'
    black_white(True);next_camera_then_cancel()
    print('Scanner: thumbnail actions',flush=True)
    drag_thumbnail_last_to_first()
    tap(description='Seitenoptionen');tap(text='Seite drehen');ready()
    rotated=draft()[1];assert rotated['pages'][rotated['selected']]['turns']==1
    last_id=rotated['pages'][rotated['selected']]['id']
    tap(description='Seitenoptionen');tap(text='Nach hinten verschieben' if rotated['selected']==0 else 'Nach vorne verschieben');ready()
    moved=draft()[1];assert moved['pages'][moved['selected']]['id']==last_id
    tap(description='Seitenoptionen');tap(text='Seitenränder bearbeiten');ready()
    tap(description='Zurück');ready()
    before_delete=len(draft()[1]['pages'])
    tap(description='Seite 1 löschen');tap(text='Abbrechen');assert len(draft()[1]['pages'])==before_delete
    tap(description='Seite 1 löschen');tap(text='Seite löschen');ready();assert len(draft()[1]['pages'])==before_delete-1
    capture();next_camera_then_cancel()
    token,before=draft();count=len(before['pages']);assert count>=2
    screenshot(out,'page-strip.png')
    print('Scanner: resume and PDF export',flush=True)
    adb('shell','am','force-stop',PACKAGE)
    adb('shell','am','start','-W','-n',PACKAGE+'/.MainActivity','-a','android.intent.action.MAIN')
    tap(description='PDF scannen');ready()
    assert draft()[0]==token and draft()[1]['pages']==before['pages']
    name='Kamera-Test-'+str(time.time_ns())
    tap(description='Exportieren');adb('shell','input','text',name);tap(text='PDF erstellen')
    wait_hierarchy(lambda ns:any(n.get('resource-id')==PACKAGE+':id/page_indicator' and n.get('text','').endswith(f' / {count}') for n in ns))
    docs=records();doc=next(d for d in docs if d['name']==name+'.pdf')
    assert doc['pageCount']==count,doc
    assert adb('shell','run-as',PACKAGE,'ls','files/scanner-drafts').strip()==''
    screenshot(out,'exported-pdf.png')
    print('PASS: real camera, top loupe, black/white toggle, explicit Next with retained preset, drag ordering, small-X confirmation, draft resume and separate Export')

if __name__=='__main__':main()
