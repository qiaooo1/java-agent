from docx import Document
from docx.oxml.ns import qn

path = 'output/两个参数的梯度下降算法代码及实验结果_修改版.docx'
doc = Document(path)
body = doc.element.body
removing = False
removed = False
for element in list(body):
    text = ''.join(node.text or '' for node in element.iter(qn('w:t')))
    if text.startswith('8.3 停下来的时候离最优还多远'):
        removing = True
        removed = True
    if text.startswith('8.4 剩下的误差出在哪个点'):
        removing = False
        for node in element.iter(qn('w:t')):
            if node.text and '8.4' in node.text:
                node.text = node.text.replace('8.4', '8.3', 1)
                break
    if removing:
        body.remove(element)
if not removed:
    raise RuntimeError('未找到第 8.3 节')
if not doc.paragraphs[-1].text.strip():
    p = doc.paragraphs[-1]._element
    p.getparent().remove(p)
doc.save('output/两个参数的梯度下降算法实验_精简版.docx')
