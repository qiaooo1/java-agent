from reportlab.pdfgen import canvas
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.lib.pagesizes import A4
from reportlab.lib.colors import HexColor
from reportlab.lib.utils import simpleSplit
from pathlib import Path

pdfmetrics.registerFont(TTFont('CN', 'C:/Windows/Fonts/msyh.ttc'))
pdfmetrics.registerFont(TTFont('Mono', 'C:/Windows/Fonts/consola.ttf'))
path = 'output/pdf/单隐层神经网络代码实现报告.pdf'
c = canvas.Canvas(path, pagesize=A4)
width, height = A4
page = 0
def new_page(title):
    global page, y
    if page: c.showPage()
    page += 1
    c.setFillColor(HexColor('#17344b')); c.setFont('CN', 13)
    c.drawString(48, height-48, title)
    c.setStrokeColor(HexColor('#d9e2e9')); c.line(48, height-57, width-48, height-57)
    c.setFillColor(HexColor('#6c7a86')); c.setFont('CN', 8)
    c.drawRightString(width-48, 27, str(page))
    c.setFillColor(HexColor('#202a32')); y = height-79
def line(text, font='CN', size=10, gap=18):
    global y
    c.setFont(font,size); c.drawString(50,y,text); y -= gap
def paragraph(text):
    global y
    text = text.replace('我', '')
    if text.startswith(('二、', '三、', '四、', '五、', '六、')):
        y -= 12
    for s in simpleSplit(text,'CN',10,width-100): line(s)
    y -= 5

new_page('单隐层全连接神经网络：代码实现报告')
paragraph('一、实现目标：我使用两个输入节点、三个隐层节点和一个输出节点的全连接神经网络，拟合函数 z = x0² + 2x1² + 3。隐层和输出层均采用 sigmoid 激活函数，使用均方误差与批量梯度下降训练。')
paragraph('二、参数配置：W1[i][j] 对应 W_ij^(1)，其形状为 2×3；b1[j] 对应 b_j^(1)。W2[j][0] 对应 W_j0^(2)，其形状为 3×1；b2[0] 对应 b_0^(2)。权重第一个下标表示前一层节点，第二个下标表示当前层节点。')
paragraph('三、训练方法：我先计算每个样本的输出及各参数梯度，将梯度求平均，再使用学习率 η 同时更新全部参数。单样本时该平均梯度即为对应的单样本梯度。')
paragraph('四、参数与数据：初始 W1 = [[0, 0, 0], [0, 0.1, 0.01]]，b1 = [[0, 0.1, -0.1]]，W2 = [[0], [-0.1], [0.1]]，b2 = [[0]]。学习率为 0.001，迭代 1000000 次。x0 在 [0, 1] 等距取 10 个值，x1 在 [1, 2] 等距取 10 个值，逐项配对生成 10 个样本。各列独立进行最大最小归一化。')
paragraph('前向传播时，我将输入矩阵 x 与 W1 相乘并加上 b1，经过 sigmoid 得到隐层输出 H；再计算 H 与 W2 的乘积，加上 b2 并经过 sigmoid，得到预测值。每行对应一个样本，H 的形状为 10×3，预测值的形状为 10×1。')
paragraph('反向传播时，我先计算输出误差项 delta2 = (预测值 - 标签) × 预测值 × (1 - 预测值)，再通过 W2 将误差传回隐层。各样本的梯度取平均后，所有权重和偏置按“旧参数 - 学习率 × 平均梯度”更新。')
paragraph('归一化公式为 (原值 - 最小值) / (最大值 - 最小值 + 1e-8)。训练后，我使用同一组输出最小值和最大值，将预测结果还原到原函数的尺度。')
paragraph('五、运行环境：Python 3，需安装 NumPy 和 Matplotlib。将代码保存为 neural_network.py 后运行：')
line('python neural_network.py', 'Mono', 10); y -= 8
paragraph('六、结果与说明：程序每隔 100000 次输出损失，训练后绘制损失曲线和训练样本真实值与预测值的对比图。由于样本逐项配对，x1 = x0 + 1；对比图仅反映这条线上训练样本的拟合情况。')
paragraph('运行验证：初始损失为 0.05364166；1000000 次更新后的损失为 0.00192570。')

new_page('训练损失曲线')
paragraph('我记录每次参数更新前的损失，并绘制训练过程中损失随迭代次数的变化。横轴为迭代次数，纵轴为归一化数据上的损失。')
c.drawImage('output/pdf/loss_curve.png', 48, y-350, width=width-96, height=350, preserveAspectRatio=True, anchor='c')

new_page('训练样本预测结果')
paragraph('我将预测值反归一化后，与对应样本的真实 z 值进行比较。横轴为 x0；每个点同时对应 x1 = x0 + 1。两条曲线的差距反映训练样本上的预测误差。')
c.drawImage('output/pdf/prediction_comparison.png', 48, y-350, width=width-96, height=350, preserveAspectRatio=True, anchor='c')

source=Path('output/pdf/neural_network.py').read_text(encoding='utf-8').splitlines()
new_page('完整代码')
for num, text in enumerate(source,1):
    if y < 45: new_page('完整代码（续）')
    c.setFillColor(HexColor('#8896a1')); c.setFont('Mono',8)
    c.drawRightString(45,y,str(num))
    c.setFillColor(HexColor('#202a32'))
    # 中文注释采用中文字体；其他代码使用等宽字体。
    font='CN' if any(ord(ch)>127 for ch in text) else 'Mono'
    line(text,font,8.3,12.3)
c.save()
