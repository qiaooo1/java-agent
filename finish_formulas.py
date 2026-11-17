from docx import Document
from lxml import etree

doc = Document('output/两个参数的梯度下降算法实验_公式优化版.docx')
transform = etree.XSLT(etree.parse('C:/Program Files/Microsoft Office/root/Office16/MML2OMML.XSL'))
yp = '<msub><mi>y</mi><mi>p</mi></msub>'
sigma = '<mi>σ</mi><mo>(</mo><mn>0.1</mn><mo>)</mo>'
delta = ('<mi>δ</mi><mo>=</mo><mo>(</mo>'+yp+'<mo>−</mo><mi>y</mi><mo>)</mo>'
         +yp+'<mo>(</mo><mn>1</mn><mo>−</mo>'+yp+'<mo>)</mo>')
def math(p, expression):
    xml = '<math xmlns="http://www.w3.org/1998/Math/MathML">'+expression+'</math>'
    p._p.append(transform(etree.fromstring(xml.encode())).getroot())
found=0
for p in doc.paragraphs:
    text=p.text
    if text.startswith('对输入 x，代码先计算加权输入'):
        p.clear(); p.add_run('对输入 x，代码先计算加权输入 I，再经过 sigmoid 激活得到预测值 ')
        math(p,yp); p.add_run('：'); found+=1
    elif text.startswith('初始 w = 0、b = 0.1 时'):
        p.clear(); p.add_run('初始 w = 0、b = 0.1 时，三个样本的 I 均为 0.1，')
        math(p,yp+'<mo>=</mo>'+sigma+'<mo>=</mo><mn>0.524979</mn>'); p.add_run('。'); found+=1
    elif text.startswith('链式法则相乘得到单样本梯度'):
        p.clear(); p.add_run('链式法则相乘得到单样本梯度，再对 3 个样本取平均。记误差信号为：'); found+=1
        # 将误差信号独立成行，避免长公式在行中断开。
        from docx.oxml import OxmlElement
        from docx.text.paragraph import Paragraph
        from docx.enum.text import WD_ALIGN_PARAGRAPH
        elem=OxmlElement('w:p'); p._p.addnext(elem)
        q=Paragraph(elem,p._parent); q.alignment=WD_ALIGN_PARAGRAPH.CENTER
        math(q,delta)
    elif text.startswith('用 y·(1 − y) 计算激活函数的导数'):
        p.clear(); p.add_run('激活函数的导数可以通过预测值计算，其推导如下：')
if found != 3: raise RuntimeError(f'匹配到 {found} 个段落，预期为 3 个')
doc.save('output/两个参数的梯度下降算法实验_公式检查版.docx')
