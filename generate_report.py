from reportlab.pdfgen import canvas
from reportlab.platypus import SimpleDocTemplate, Paragraph, Spacer, PageBreak
from reportlab.lib.styles import ParagraphStyle
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.lib.enums import TA_CENTER
from reportlab.lib.colors import HexColor
from reportlab.lib.units import mm
import os

os.makedirs('output/pdf', exist_ok=True)
pdfmetrics.registerFont(TTFont('CN', 'C:/Windows/Fonts/msyh.ttc'))
styles={
 'title':ParagraphStyle('title',fontName='CN',fontSize=19,leading=28,alignment=TA_CENTER,spaceAfter=15,textColor=HexColor('#17344b')),
 'h':ParagraphStyle('h',fontName='CN',fontSize=13,leading=20,spaceBefore=8,spaceAfter=5,textColor=HexColor('#17344b')),
 'p':ParagraphStyle('p',fontName='CN',fontSize=10,leading=17,spaceAfter=5),
 'eq':ParagraphStyle('eq',fontName='CN',fontSize=10,leading=19,leftIndent=14,spaceAfter=3),
}
story=[]
def add(s,k='p'): story.append(Paragraph(s,styles[k]))
def eq(s): add(s,'eq')
def page(): story.append(PageBreak())
def w(i,j,l): return f'W<sub>{i}{j}</sub><super>({l})</super>'
def b(i,l): return f'b<sub>{i}</sub><super>({l})</super>'
def I(i,l): return f'I<sub>{i}</sub><super>({l})</super>'
def O(i,l): return f'O<sub>{i}</sub><super>({l})</super>'
def d(v): return f'∂L / ∂{v}'
out=O(0,2); h=[O(i,1) for i in range(3)]
add('单隐层全连接神经网络<br/>梯度下降算法的理论推导','title')
add('一、网络结构与记号','h')
add('我采用的网络有两个输入节点 X<sub>0</sub>、X<sub>1</sub>，三个隐层节点和一个输出节点。权重 W<sub>ij</sub><super>(l)</super> 的第一个下标表示前一层节点，第二个下标表示当前层节点；b<sub>i</sub><super>(l)</super>、I<sub>i</sub><super>(l)</super>、O<sub>i</sub><super>(l)</super> 分别表示第 l 层第 i 个节点的偏置、加权输入和输出。η 为学习率。')
add('我选用 sigmoid 激活函数，其表达式及导数为：')
eq('f(z) = 1 / (1 + e<super>−z</super>)，　f′(z) = f(z)[1 − f(z)]')
add('二、前向传播','h')
for j in range(3): eq(f'{I(j,1)} = X<sub>0</sub>{w(0,j,1)} + X<sub>1</sub>{w(1,j,1)} + {b(j,1)}')
for j in range(3): eq(f'{O(j,1)} = f({I(j,1)})')
eq(f'{I(0,2)} = {h[0]}{w(0,0,2)} + {h[1]}{w(1,0,2)} + {h[2]}{w(2,0,2)} + {b(0,2)}')
eq(f'{out} = f({I(0,2)})')
add('三、损失函数','h')
add('对 n 个样本，均方误差定义为：')
eq('L = (1 / 2n) Σ<sub>i=1</sub><super>n</super> (O<sub>0,i</sub><super>(2)</super> − y<sub>i</sub>)<super>2</super>')
add('下面我以单个样本为例推导，令 n = 1，单样本损失为 L = ½(' + out + ' − y)<super>2</super>。多样本时，对各样本的相应梯度求平均。')
page()
add('四、输出层的反向传播','h')
add('根据链式法则，输出层节点误差信号中的各因子为：')
eq(f'∂L / ∂{out} = {out} − y')
eq(f'∂{out} / ∂{I(0,2)} = {out}(1 − {out})')
eq(f'∂{I(0,2)} / ∂{b(0,2)} = 1，　∂{I(0,2)} / ∂{w(0,0,2)} = {h[0]}')
add('因此，输出层偏置及三个权重的梯度依次为：')
eq(f'{d(b(0,2))} = (∂L / ∂{out})(∂{out} / ∂{I(0,2)})(∂{I(0,2)} / ∂{b(0,2)})')
eq(f'= ({out} − y){out}(1 − {out})')
for j in range(3):
 eq(f'{d(w(j,0,2))} = ({out} − y){out}(1 − {out}){h[j]}')
add('其中每个权重的最后一个因子是该权重对应的隐层节点输出；输出层偏置的最后一个因子为 1。')
add('五、隐层的反向传播','h')
add('隐层节点通过输出层影响损失。对于第 j 个隐层节点（j = 0, 1, 2），我根据链式法则得到：')
eq(f'{d(b("j",1))} = (∂L / ∂{out})(∂{out} / ∂{I(0,2)})(∂{I(0,2)} / ∂{O("j",1)})')
eq(f'　　　　　× (∂{O("j",1)} / ∂{I("j",1)})(∂{I("j",1)} / ∂{b("j",1)})')
eq(f'∂{I(0,2)} / ∂{O("j",1)} = {w("j",0,2)}，　∂{I("j",1)} / ∂{b("j",1)} = 1')
eq(f'∂{O("j",1)} / ∂{I("j",1)} = {O("j",1)}(1 − {O("j",1)})')
add('代入后，三个偏置梯度分别是：')
for j in range(3): eq(f'{d(b(j,1))} = ({out} − y){out}(1 − {out}){w(j,0,2)}{h[j]}(1 − {h[j]})')
page()
add('六、隐层权重的梯度','h')
add('对权重 W<sub>ij</sub><super>(1)</super>，链式法则与隐层偏置相同，只需将最后一项替换为：')
eq(f'∂{I("j",1)} / ∂{w("i","j",1)} = X<sub>i</sub>，　i = 0, 1；j = 0, 1, 2')
eq(f'{d(w("i","j",1))} = (∂L / ∂{out})(∂{out} / ∂{I(0,2)})(∂{I(0,2)} / ∂{O("j",1)})')
eq(f'　　　　　 × (∂{O("j",1)} / ∂{I("j",1)})(∂{I("j",1)} / ∂{w("i","j",1)})')
add('逐项展开，得到六个权重的梯度：')
for j in range(3):
 for i in range(2): eq(f'{d(w(i,j,1))} = ({out} − y){out}(1 − {out}){w(j,0,2)}{h[j]}(1 − {h[j]})X<sub>{i}</sub>')
add('七、梯度下降更新','h')
add('所有梯度先使用同一次前向传播的原参数计算，再同时更新。输出层参数更新为：')
eq(f'{b(0,2)} ← {b(0,2)} − η {d(b(0,2))}')
for j in range(3): eq(f'{w(j,0,2)} ← {w(j,0,2)} − η {d(w(j,0,2))}')
add('隐层三个偏置和六个权重按相同规则更新：')
eq(f'{b("j",1)} ← {b("j",1)} − η {d(b("j",1))}，　j = 0, 1, 2')
eq(f'{w("i","j",1)} ← {w("i","j",1)} − η {d(w("i","j",1))}，　i = 0, 1；j = 0, 1, 2')
add('我将隐层权重排列为两行三列：第一行为 W<sub>00</sub><super>(1)</super>、W<sub>01</sub><super>(1)</super>、W<sub>02</sub><super>(1)</super>；第二行为 W<sub>10</sub><super>(1)</super>、W<sub>11</sub><super>(1)</super>、W<sub>12</sub><super>(1)</super>。梯度矩阵按对应位置排列后，整体减去 η 倍梯度矩阵。')
add('重复前向传播、计算损失、反向传播及参数更新，直到达到设定的停止条件。')
def footer(c,doc):
 c.setFont('CN',8); c.setFillColor(HexColor('#687a88')); c.drawRightString(195*mm,12*mm,f'{doc.page}')
doc=SimpleDocTemplate('output/pdf/单隐层神经网络梯度下降推导.pdf',pagesize=(210*mm,297*mm),leftMargin=20*mm,rightMargin=20*mm,topMargin=17*mm,bottomMargin=18*mm)
doc.build(story,onFirstPage=footer,onLaterPages=footer)
