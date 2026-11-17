from docx import Document
from docx.enum.text import WD_ALIGN_PARAGRAPH
from lxml import etree

src = 'output/两个参数的梯度下降算法实验_精简版.docx'
out = 'output/两个参数的梯度下降算法实验_公式优化版.docx'
doc = Document(src)
transform = etree.XSLT(etree.parse('C:/Program Files/Microsoft Office/root/Office16/MML2OMML.XSL'))
def mi(s): return f'<mi>{s}</mi>'
def mo(s): return f'<mo>{s}</mo>'
def num(s): return f'<mn>{s}</mn>'
def row(s): return f'<mrow>{s}</mrow>'
def sub(a,b): return f'<msub>{mi(a)}{mi(b)}</msub>'
def sup(a,b): return f'<msup>{row(a)}{row(b)}</msup>'
def frac(a,b): return f'<mfrac>{row(a)}{row(b)}</mfrac>'
def group(s): return mo('(')+s+mo(')')
def term(a): return mi(a)+mo('(')+mi('I')+mo(')')
yp=sub('y','p'); sigmoid=term('σ'); one=num('1'); zero=num('0'); diff=mo('−'); plus=mo('+'); eq=mo('='); mult=mo('·')
deriv=lambda a,b: frac(mo('∂')+a,mo('∂')+b)
sigma_prime=sup(mi('σ'),mo('′'))+group(mi('I'))
exp=sup(mi('e'),row(diff+mi('I')))
one_minus_yp=group(one+diff+yp)
error=group(yp+diff+mi('y'))
forms={
 'I = w·x + b': mi('I')+eq+mi('w')+mi('x')+plus+mi('b'),
 'yp = activation(I) = σ(I) = 1 / (1 + e^(−I))': yp+eq+mi('activation')+group(mi('I'))+eq+sigmoid+eq+frac(one,one+plus+exp),
 'σ(I) = (1 + e^(−I))^(−1)': sigmoid+eq+sup(group(one+plus+exp),row(diff+one)),
 'σ′(I) = −(1 + e^(−I))^(−2)·(−e^(−I)) = e^(−I) / (1 + e^(−I))²': sigma_prime+eq+frac(exp,sup(group(one+plus+exp),num('2'))),
 'σ′(I) = σ(I)·(1 − σ(I)) = yp·(1 − yp)': sigma_prime+eq+sigmoid+group(one+diff+sigmoid)+eq+yp+one_minus_yp,
 'L = 0.5·(yp − y)²': mi('L')+eq+frac(one,num('2'))+sup(error,num('2')),
 'l = (1/3)·Σ 0.5·(yp − y)²': mi('l')+eq+frac(one,num('3'))+mo('∑')+frac(one,num('2'))+sup(error,num('2')),
 'dL/dyp = yp − y\u3000\u3000dyp/dI = yp·(1 − yp)': deriv(mi('L'),yp)+eq+yp+diff+mi('y')+mo(',')+mo(' ')+deriv(yp,mi('I'))+eq+yp+one_minus_yp,
 'dI/dw = x,\u3000dI/db = 1': deriv(mi('I'),mi('w'))+eq+mi('x')+mo(',')+mo(' ')+deriv(mi('I'),mi('b'))+eq+one,
 'nabla_w = (1/3)·Σ δ·x\u3000\u3000nabla_b = (1/3)·Σ δ': sub('∇','w')+eq+frac(one,num('3'))+mo('∑')+mi('δ')+mi('x')+mo(',')+mo(' ')+sub('∇','b')+eq+frac(one,num('3'))+mo('∑')+mi('δ'),
 'w = w − eta·nabla_w': mi('w')+mo('←')+mi('w')+diff+mi('η')+sub('∇','w'),
 'b = b − eta·nabla_b': mi('b')+mo('←')+mi('b')+diff+mi('η')+sub('∇','b'),
}
found=set()
for p in doc.paragraphs:
    key=p.text.replace('\u3000\u3000\u3000', '\u3000\u3000')
    if key in forms:
        for child in list(p._p):
            if child.tag.endswith('}r'): p._p.remove(child)
        math = etree.fromstring(('<math xmlns="http://www.w3.org/1998/Math/MathML">'+forms[key]+'</math>').encode())
        omml=transform(math).getroot()
        p._p.append(omml)
        p.alignment=WD_ALIGN_PARAGRAPH.CENTER
        found.add(key)
missing=set(forms)-found
if missing: raise RuntimeError('Missing: '+repr(missing))
doc.save(out)
