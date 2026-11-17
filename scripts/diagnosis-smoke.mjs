import { randomUUID } from 'node:crypto'; // 独立账号与幂等键，不污染已有任务。
import { mkdir, writeFile } from 'node:fs/promises'; // 保存可以审查的链路结果。
const base = process.env.BASE_URL || 'http://localhost:18080'; // 默认经网关验证完整 HTTP 链路。
async function api(path, token, body, key, expected = 200) { // 请求不打印密码和令牌。
  const response = await fetch(base + path, { method: body ? 'POST' : 'GET', headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: 'Bearer ' + token } : {}), ...(key ? { 'Idempotency-Key': key } : {}) }, body: body ? JSON.stringify(body) : undefined }); // 真实鉴权和幂等提交。
  const text = await response.text(); if (response.status !== expected) throw new Error(`${path}: HTTP ${response.status}, expected ${expected}`); // 失败不吞掉。
  return text ? JSON.parse(text) : {}; // 这些 API 正常响应均为 JSON。
} // 结束请求助手。
async function register() { return (await api('/api/auth/register', '', { username: 'diag_' + randomUUID().replaceAll('-', '').slice(0, 12), password: 'diag-' + randomUUID() })).accessToken; } // 每次运行创建全新用户。
let ready = false; for (let i = 0; i < 30; i++) { try { ready = (await fetch(base + '/actuator/health')).ok; } catch { ready = false; } if (ready) break; await new Promise(resolve => setTimeout(resolve, 1000)); } // 只在验收前等待网关启动，不重试已失败的业务断言。
if (!ready) throw new Error('网关未在启动窗口内就绪'); // 环境失败与业务失败区分。
const owner = await register(), other = await register(), catalog = await api('/api/incidents/catalog', owner); // 两个身份验证资源隔离。
if (!catalog.synthetic) throw new Error('该脚本只用于明确标记的合成演示环境'); // 不误当生产排障实验。
const to = new Date().toISOString(), from = new Date(Date.now() - 15 * 60000).toISOString(); // 所有案例使用相同时间窗口。
const report = { createdAt: new Date().toISOString(), kind: 'workflow-smoke-not-agent-evaluation', cases: [] }; // 不能把规则脚本成绩称为 Agent 成功率。
for (const environment of catalog.environments) { // 三类故障加信息不足路径。
  const body = { goal: '任务持续排队，请调查原因并给出有引用的报告，不要执行修复。', knowledgeId: null, maxSteps: 8, diagnosis: { service: catalog.service, environment, from, to } }, key = randomUUID(); // 范围冻结并可回放。
  const task = await api('/api/incidents', owner, body, key), duplicate = await api('/api/incidents', owner, body, key); // 相同请求重复提交。
  if (task.id !== duplicate.id) throw new Error('工单幂等失败'); // 必须归并为一个任务。
  await api(`/api/incidents/${task.id}`, other, null, null, 404); // 工单人工状态不可越权读取。
  let completed; const deadline = Date.now() + 120000; // 有界等待，不能无限轮询掩盖问题。
  while (Date.now() < deadline) { completed = await api(`/api/tasks/${task.id}`, owner); if (['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(completed.state)) break; await new Promise(resolve => setTimeout(resolve, 1000)); } // 沿用真实异步任务生命周期。
  if (completed.state !== 'SUCCEEDED') throw new Error(`调查未完成：${completed.error || completed.state}`); // 失败报告不能混入通过数。
  const result = JSON.parse(completed.answer), ids = new Set(result.evidence.map(item => item.id)); // 引用只来自本次返回的证据。
  if (result.mode !== 'DEMO_SCRIPT' || !result.syntheticEvidence) throw new Error('此脚本只验证规则演示链路，请使用独立真实模型评估'); // 拒绝混淆模型与演示。
  if (!result.report.findings.length || result.report.findings.some(item => !item.evidenceIds.length || item.evidenceIds.some(id => !ids.has(id)))) throw new Error('报告缺少有效引用'); // 这只能证明存在性，不能证明结论语义正确。
  if (environment === 'demo-d' && result.report.outcome !== 'NEEDS_INFORMATION') throw new Error('信息不足应请求补充证据'); // 保留未知项。
  await api(`/api/incidents/${task.id}/review`, other, { decision: 'CONFIRMED', note: '越权测试' }, null, 404); // 知道任务编号也不能处理。
  const review = await api(`/api/incidents/${task.id}/review`, owner, { decision: environment === 'demo-d' ? 'ESCALATED' : 'CONFIRMED', note: '本地演示核对，未执行修复。' }); // 人工交接与执行结果独立。
  report.cases.push({ environment, taskId: task.id, state: completed.state, mode: result.mode, outcome: result.report.outcome, evidenceCount: ids.size, review: review.state }); // 不记录访问凭据。
} // 结束一次纵向回归。
await api('/api/incidents', owner, { goal: '越界', maxSteps: 8, diagnosis: { service: 'production', environment: 'demo-a', from, to } }, randomUUID(), 400); // 无法指定任意生产资源。
const quota = await api('/api/quota', owner); if (quota.usedTasks !== 4 || quota.reservedTasks !== 0) throw new Error('重复提交或人工处理影响了次数结算'); // 四份报告只结算四次。
report.quota = quota; await mkdir('target', { recursive: true }); await writeFile('target/diagnosis-smoke-report.json', JSON.stringify(report, null, 2)); // 保存原始验收结果。
console.log(`PASS: ${report.cases.length} incident workflows; scoped evidence, idempotency, ownership, review, quota`); // 只报告链路通过，不报告模型成功率。
