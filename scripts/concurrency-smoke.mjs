import { randomUUID } from 'node:crypto'; // 测试账号和请求标识独立于已有用户。
import { mkdir, writeFile } from 'node:fs/promises'; // 保存真实请求结果，便于复核。
const base = process.env.BASE_URL || 'http://localhost:18080'; // 默认验证完整网关路径。
async function api(path, token, body, key, expected = 200) { // 使用标准身份和幂等头发起 HTTP 请求。
  const response = await fetch(base + path, { method: body ? 'POST' : 'GET', headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: 'Bearer ' + token } : {}), ...(key ? { 'Idempotency-Key': key } : {}) }, body: body ? JSON.stringify(body) : undefined }); // 不通过数据库绕过正常业务流程。
  const text = await response.text(); if (response.status !== expected) throw new Error(`${path}: expected ${expected}, got ${response.status}`); return text ? JSON.parse(text) : {}; // 所有状态必须匹配预期。
} // 结束 API 请求助手。
async function register() { return api('/api/auth/register', '', { username: 'check_' + randomUUID().replaceAll('-', '').slice(0, 12), password: 'check-password-' + randomUUID() }); } // 不打印临时令牌。
const auth = await register(), other = await register(), token = auth.accessToken; // 为越权场景创建第二个用户。
const metrics = await fetch('http://localhost:18090/metrics').then(response => response.json()); // 取消场景要求使用慢模拟模型。
if (metrics.delayMs < 8000) throw new Error('请先使用至少八秒延迟的本地模型模拟器'); // 不对真实模型产生测试费用。
async function wait(id, predicate) { // 等待状态必须有时间上限。
  const deadline = Date.now() + 45000; // 完成和取消各最多等待四十五秒。
  while (Date.now() < deadline) { const task = await api('/api/tasks/' + id, token); if (predicate(task)) return task; await new Promise(resolve => setTimeout(resolve, 1000)); } // 每秒查询一次。
  throw new Error('任务未在观察窗口内达到预期状态'); // 超时绝不算验证成功。
} // 结束有界等待。
const key = randomUUID(), input = { goal: '本地并发幂等验证', knowledgeId: null, maxSteps: 3 }; // 所有并发提交使用完全相同的请求。
const submitted = await Promise.all(Array.from({ length: 16 }, () => api('/api/tasks', token, input, key))); // 同时发起十六个真实网络请求。
const ids = new Set(submitted.map(task => task.id)); if (ids.size !== 1) throw new Error('并发提交创建了多个任务'); // 检查服务端幂等而非前端按钮禁用。
const id = submitted[0].id, completed = await wait(id, task => ['SUCCEEDED', 'FAILED'].includes(task.state)); // 等待唯一任务执行结束。
const attempts = await api(`/api/tasks/${id}/attempts`, token); // 检查消息与执行层没有重复运行。
if (completed.state !== 'SUCCEEDED' || attempts.length !== 1 || !completed.answer?.startsWith('[MOCK]')) throw new Error('任务未由单次模拟执行完成'); // 不能把演示短路当成真实 HTTP 模型路径。
await api('/api/tasks/' + id, other.accessToken, null, null, 404); // 用户之间不能读取任务。
await api('/api/tasks/' + id, '', null, null, 401); // 没有令牌也不能读取任务。
const cancellable = await api('/api/tasks', token, { goal: '本地运行中取消验证', knowledgeId: null, maxSteps: 3 }, randomUUID()); // 使用第二个独立请求测试取消。
await wait(cancellable.id, task => task.state === 'RUNNING'); // 必须在真正执行中取消，不能只测排队取消。
const cancelStart = Date.now(), requested = await api(`/api/tasks/${cancellable.id}/cancel`, token, {}); // 用户请求只应产生中间态。
if (requested.state !== 'CANCEL_REQUESTED') throw new Error('运行中取消没有经过请求状态'); // 验证取消语义。
const cancelled = await wait(cancellable.id, task => ['CANCELLED', 'SUCCEEDED', 'FAILED'].includes(task.state)); // 执行端确认之后才认定取消。
if (cancelled.state !== 'CANCELLED') throw new Error('运行中任务未完成取消'); // 模型正常返回也不能覆盖用户取消意图。
const quota = await api('/api/quota', token); if (quota.usedTasks !== 1 || quota.reservedTasks !== 0) throw new Error('成功与取消任务结算不一致'); // 成功扣一次，取消释放预留。
const report = { completedAt: new Date().toISOString(), concurrentRequests: submitted.length, uniqueTaskIds: ids.size, executionAttempts: attempts.length, taskId: id, cancellationTaskId: cancellable.id, cancelRequestedState: requested.state, finalCancellationState: cancelled.state, observedCancellationMs: Date.now() - cancelStart, quota }; // 时延只代表该次本地实验。
await mkdir('target', { recursive: true }); await writeFile('target/concurrency-smoke-report.json', JSON.stringify(report, null, 2)); // 将可核对事实保留到文件。
console.log('PASS: 16 concurrent submissions -> 1 task -> 1 attempt; permissions, cancellation and quota verified'); // 全部验证完成才输出通过。
