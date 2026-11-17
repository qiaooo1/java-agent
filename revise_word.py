from docx import Document
from pathlib import Path

src = r'G:/新建文件夹/两个参数的梯度下降算法代码及实验结果.docx'
out = Path('output/两个参数的梯度下降算法代码及实验结果_修改版.docx')
out.parent.mkdir(exist_ok=True)
doc = Document(src)
changes = {
 '1. 理解单神经元': '1. 理解单神经元的前向传播、反向传播和梯度下降过程。',
 '2. 通过手写批量': '2. 编写梯度下降代码，观察损失和参数的变化。',
 '3. 验证推导': '3. 检查梯度计算，并比较不同学习率下的训练效果。',
 '4. 认识单神经元': '4. 观察单神经元对函数的拟合效果。',
 '代码文件 bp_neuron_v2.py': '使用批量梯度下降训练一个 sigmoid 单神经元，并在测试集上计算预测结果。主要参数如下：',
 '训练流程：每个 epoch': '每轮使用全部 3 个训练样本计算平均梯度并更新参数，训练结束后在测试集上计算损失。',
 '两边小数点后七八位': '解析梯度与数值梯度基本一致，说明梯度计算正确。',
 '把 eta 从 0.001': '保持其他条件不变，分别设置不同的学习率，训练 20000 轮，结果如下：',
 'eta = 0.001 时我特意': '学习率为 0.001 时，损失从 0.09614 降到 0.07137，下降较慢。',
 '这件事说白了就是': '学习率过小时收敛较慢。本实验选择 0.5，训练过程中损失稳定下降。',
 '用 scipy 的 Nelder-Mead': '使用 scipy 的 Nelder-Mead 方法进行数值优化，得到一组损失小于 1e-15 的参数：w ≈ 107.01、b ≈ −54.60。由于 sigmoid 无法在有限参数下恰好输出 0 或 1，这组参数是数值优化结果。',
 '看图 8-1(a)': '从图中可以看到损失谷底较狭长，参数在进入谷底后更新变慢；训练 20000 轮时 w 约为 9。',
 '95% 的误差': '训练误差主要来自 x = 1 的样本，占总损失约 95%。该点的目标值为 1，而 sigmoid 输出只能逐渐接近 1。',
 '测试集上偏得最厉害': '测试集中 x = 0.8 的误差较大，预测值为 0.8364，真实值为 0.64。单个 sigmoid 曲线不能完全拟合 x² 的形状。',
 '（1）收敛是前快后慢': '（1）训练损失先下降较快，后期下降变慢。参数增大后 sigmoid 导数减小，梯度随之变小。',
 '（2）学习率不能凭感觉给': '（2）学习率会影响收敛速度。本实验中，学习率 0.001 的下降较慢，0.5 时损失下降较明显。',
 '（3）单神经元的本事有限': '（3）单神经元可以拟合训练样本，但在测试点仍有误差。增加隐藏层可以提高模型的表达能力。',
 '这次也踩了个坑': '',
}
seen=set()
for p in doc.paragraphs:
    for prefix, replacement in changes.items():
        if p.text.startswith(prefix):
            if p.runs:
                p.runs[0].text = replacement
                for run in p.runs[1:]: run.text = ''
            else: p.add_run(replacement)
            seen.add(prefix)
            break
missing = set(changes)-seen
if missing: raise RuntimeError('Unmatched paragraphs: '+repr(missing))
doc.save(out)
print(out.resolve())
