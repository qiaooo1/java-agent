import http from 'k6/http'; // k6 提供并发 HTTP 压测，脚本不会凭空产生性能结论。
import { check, sleep } from 'k6'; // 分别统计业务校验与用户间隔。
export const options = { vus: Number(__ENV.VUS || 5), duration: __ENV.DURATION || '20s', thresholds: { checks: ['rate>0.99'], http_req_failed: ['rate<0.01'] } }; // 默认低负载，先验证正确性再逐步加压。
export default function () { // 每个虚拟用户需要自己的已注册账号和令牌，避免混淆单用户限流与系统容量。
  const tokens = (__ENV.TOKENS || '').split(',').filter(Boolean); // 通过环境变量注入令牌，不提交到仓库。
  if (!tokens.length) throw new Error('Provide TOKENS from registered benchmark users'); // 不自动注册污染认证压测指标。
  const token = tokens[(__VU - 1) % tokens.length]; // 多个用户分摊负载，令牌数量必须写入报告。
  const response = http.post(`${__ENV.BASE_URL || 'http://localhost:18080'}/api/tasks`, JSON.stringify({ goal: 'Calculate 1+2', knowledgeId: null, maxSteps: 3 }), { headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json', 'Idempotency-Key': `load-${__VU}-${__ITER}-${Date.now()}` }, tags: { operation: 'task_submit' } }); // 只测受理接口，不声称模型完成吞吐。
  check(response, { 'task accepted': r => r.status === 200 && Boolean(r.json('id')) }); // 429 表示限额或保护命中，需要单独解释而非视为成功受理。
  sleep(Number(__ENV.PAUSE_SECONDS || 3)); // 遵守默认每用户提交频率，压测参数需随实验目标调整。
} // 完成一次提交场景。
