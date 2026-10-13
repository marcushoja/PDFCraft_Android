"""Generate an offline JPEG-heavy PDF fixture (requires Pillow, not an app dependency)."""
import argparse
import io
import os
from pathlib import Path
from PIL import Image


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output',type=Path,default=Path('/tmp/opencode/images-150mb.pdf'))
    parser.add_argument('--mib',type=int,default=150)
    args=parser.parse_args()
    if not 1<=args.mib<=1024:parser.error('--mib must be between 1 and 1024')
    image_buffer=io.BytesIO()
    Image.frombytes('RGB',(2048,2048),os.urandom(2048*2048*3)).save(image_buffer,'JPEG',quality=95)
    image=image_buffer.getvalue()
    count=max(1,round(args.mib*1024*1024/len(image)))
    args.output.parent.mkdir(parents=True,exist_ok=True)
    # Never overwrite an existing user file.
    with args.output.open('xb') as stream:
        stream.write(b'%PDF-1.4\n%\xe2\xe3\xcf\xd3\n')
        offsets=[0]
        def obj(index,data):
            assert index==len(offsets)
            offsets.append(stream.tell())
            stream.write(f'{index} 0 obj\n'.encode()+data+b'\nendobj\n')
        obj(1,b'<< /Type /Catalog /Pages 2 0 R >>')
        kids=' '.join(f'{3+index*3} 0 R' for index in range(count))
        obj(2,f'<< /Type /Pages /Count {count} /Kids [{kids}] >>'.encode())
        for index in range(count):
            page=3+index*3
            obj(page,f'<< /Type /Page /Parent 2 0 R /MediaBox [0 0 600 900] /Resources << /XObject << /Image {page+2} 0 R >> >> /Contents {page+1} 0 R >>'.encode())
            draw=b'q 560 0 0 760 20 70 cm /Image Do Q\n'
            obj(page+1,f'<< /Length {len(draw)} >>\nstream\n'.encode()+draw+b'endstream')
            obj(page+2,f'<< /Type /XObject /Subtype /Image /Width 2048 /Height 2048 /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length {len(image)} >>\nstream\n'.encode()+image+b'\nendstream')
        xref=stream.tell()
        stream.write(f'xref\n0 {len(offsets)}\n0000000000 65535 f \n'.encode())
        for offset in offsets[1:]:stream.write(f'{offset:010} 00000 n \n'.encode())
        stream.write(f'trailer\n<< /Size {len(offsets)} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n'.encode())
    print(f'{args.output}: {count} JPEG pages, {args.output.stat().st_size/1048576:.2f} MiB')


if __name__=='__main__':main()
