import importlib.util, sys
from pathlib import Path
import pypdfium2 as pdfium
from pypdf import PdfReader
from PIL import Image, ImageDraw
root=Path('G:/agent4/output')
spec=importlib.util.spec_from_file_location('render_docx','C:/Users/DELL/.codex/plugins/cache/openai-primary-runtime/documents/26.1105.12137/skills/documents/render_docx.py')
m=importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
m.convert_to_pdf=lambda *a,**k:(str(root/'interview-qa.pdf'),'Rendered with Microsoft Word')
def convert(pdf, dpi, output_folder, **kwargs):
    paths=[]
    with pdfium.PdfDocument(pdf) as d:
        for i,p in enumerate(d):
            path=str(Path(output_folder)/f'page0001-{i+1:02d}.png')
            p.render(scale=dpi/72).to_pil().save(path); paths.append(path)
    return paths
m.convert_from_path=convert
sys.argv=['render_docx.py',str(root/'乔承锦_面试深度追问与参考回答.docx'),'--output_dir',str(root/'qa'),'--dpi','120']
m.main()
files=sorted((root/'qa').glob('page-*.png'), key=lambda p:int(p.stem.split('-')[-1]))
canvas=Image.new('RGB',(4*420,((len(files)+3)//4)*615),'#cccccc'); draw=ImageDraw.Draw(canvas)
for i,p in enumerate(files):
    im=Image.open(p); im.thumbnail((410,580)); x=(i%4)*420+5; y=(i//4)*615+25; canvas.paste(im,(x,y)); draw.text((x,y-20),p.stem,fill='black')
canvas.save(root/'qa/contact.png')
pdf=PdfReader(root/'interview-qa.pdf')
print('Pages:',len(pdf.pages))
for i,p in enumerate(pdf.pages): print(i+1,len(p.extract_text()),p.extract_text().splitlines()[0])
