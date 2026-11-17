import numpy as np
x=np.array([0.,.5,1.]); y=np.array([0.,.25,1.])
def calc(w,b):
    p=1/(1+np.exp(-(w*x+b)))
    d=(p-y)*p*(1-p)
    return np.mean(.5*(p-y)**2), np.mean(d*x),np.mean(d)
for eta in [.001,.01,.1,.5,1.,2.,3.]:
    w,b=0.,.1; hit=None
    for e in range(20000):
        loss,dw,db=calc(w,b)
        if hit is None and loss<.001: hit=e
        w-=eta*dw; b-=eta*db
    print(eta, f'{loss:.8f}', hit, f'{w:.4f}', f'{b:.4f}')
print('initial',calc(0,.1))
print('trained',calc(8.9955,-5.5647))
