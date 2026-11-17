import sys
sys.path.insert(0, 'output/pdf')
from neural_network import NeuralNetwork
n = NeuralNetwork()
X, y = [0.3, 0.7], 1.0
dw1, db1, dw2, db2 = n.gradients(X, y)
checks = []
for i in range(2):
    for j in range(3): checks.append((n.W1[i], j, dw1[i][j]))
for j in range(3):
    checks.append((n.b1, j, db1[j]))
    checks.append((n.W2[j], 0, dw2[j][0]))
checks.append((n.b2, 0, db2[0]))
errors = []
for values, index, analytical in checks:
    old = values[index]
    values[index] = old + 1e-6
    plus = n.loss([(X, y)])
    values[index] = old - 1e-6
    minus = n.loss([(X, y)])
    values[index] = old
    errors.append(abs(analytical - (plus - minus) / 2e-6))
print('parameters:', len(checks), 'maximum absolute error:', max(errors))
assert max(errors) < 1e-8
