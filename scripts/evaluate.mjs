import { cases, knowledge } from '../evaluation/cases.mjs'; // 使用固定任务集而非每次随机出题。
import { mkdir, writeFile } from 'node:fs/promises'; // 输出可审查的机器报告。
import { randomUUID } from 'node:crypto'; // 为评估账号和请求生成唯一标识。
const base = process.env.BASE_URL || 'http://localhost:18080'; // 可以分别对网关或平台进行实验。
let token = process.env.EVAL_TOKEN || ''; // 令牌只从环境或临时登录读取，不提交到仓库。
async function api(path, body, key) { // 统一请求协议与失败处理。
  const response = await fetch(base + path, { method: body ? 'POST' : 'GET', headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: 'Bearer ' + token } : {}), ...(key ? { 'Idempotency-Key': key } : {}) }, body: body ? JSON.stringify(body) : undefined }); // 幂等键只用于任务提交。
  const text = await response.text(); let data; try { data = text ? JSON.parse(text) : {}; } catch { data = {}; } // 网关错误不一定是 JSON。
  if (!response.ok) throw new Error(`HTTP ${response.status}: ${data.message || 'request failed'}`); // 不把限流拒绝当作任务失败效果。
  return data; // 返回业务对象。
} // 结束请求封装。
if (!token) { const auth = await api('/api/auth/register', { username: 'eval_' + randomUUID().replaceAll('-', '').slice(0, 12), password: 'eval-password-' + randomUUID() }); token = auth.accessToken; } // 独立评估账号避免污染已有用户额度。
const document = await api('/api/knowledge', knowledge); // 每次评估固定输入文档。
const report = { createdAt: new Date().toISOString(), base, mode: 'real-model-required', cases: [] }; // 报告明确环境与时间。
for (const sample of cases) { // 顺序评估避免把并发调度噪声混入模型效果。
  const start = Date.now(); const task = await api('/api/tasks', { goal: sample.goal, knowledgeId: sample.knowledge ? document.id : null, maxSteps: 6 }, randomUUID()); // 使用固定输入创建真实任务。
  let completed; // 保存实际终态。
  while (Date.now() - start < 180000) { // 每个任务最长等待三分钟。
    completed = await api('/api/tasks/' + task.id); if (['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(completed.state)) break; // 只接受明确终态。
    await new Promise(resolve => setTimeout(resolve, 2000)); // 控制轮询频率，避免消耗全部读限额。
  } // 完成终态等待。
  if (completed?.answer?.includes('演示任务已完成') || completed?.answer?.startsWith('[MOCK]')) throw new Error('检测到演示或模拟模型：请配置真实模型后评估，不生成虚假的 Agent 成功率'); // 平台压测模拟器同样不能用于证明 Agent 效果。
  const events = await api(`/api/tasks/${task.id}/events?after=0`), attempts = await api(`/api/tasks/${task.id}/attempts`); // 读取工具证据和已知用量。
  const answer = completed?.answer || ''; const answerMatched = sample.expected.every(value => answer.includes(value)); // 规则只衡量预先定义的检查项。
  const toolMatched = events.some(event => event.type === 'action' && event.data.startsWith(sample.tool + ' ')); // 不能只看答案对不对，还要验证要求的工具调用。
  report.cases.push({ id: sample.id, taskId: task.id, state: completed?.state, passed: completed?.state === 'SUCCEEDED' && answerMatched && toolMatched, answerMatched, toolMatched, observedLatencyMs: Date.now() - start, answer, attempts }); // 时延包含排队与轮询观察误差，不能冒充纯模型耗时。
  console.log(`${sample.id}: ${report.cases.at(-1).passed ? 'PASS' : 'FAIL'}`); // 控制台不输出访问令牌或密码。
} // 完成固定任务集。
report.passed = report.cases.filter(sample => sample.passed).length; report.total = report.cases.length; // 使用真实分母汇总结果。
await mkdir('target', { recursive: true }); await writeFile('target/evaluation-report.json', JSON.stringify(report, null, 2)); // 机器报告不属于需要逐行注释的源码。
console.log(`Report: target/evaluation-report.json (${report.passed}/${report.total})`); // 保留证据而非只显示最终成功率。
if (report.passed !== report.total) process.exitCode = 1; // 失败场景使自动化返回失败状态。
