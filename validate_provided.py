import sys
sys.path.insert(0, 'output/pdf')
import neural_network as net
data, minimum, maximum = net.max_min_norm(net.generate_dataset())
x, y = data[:, :2], data[:, 2:]
for e in range(net.epoch):
    H, pred = net.model(x)
    if e in (0, 100000, 500000, 999999):
        print(e, f'{net.loss(pred, y):.10f}', flush=True)
    net.step(pred, x, y, H)
print('final', f'{net.loss(net.model(x)[1], y):.10f}', flush=True)
