import { randomUUID } from 'node:crypto'; // 两个身份用于验证文档归属隔离。
import { mkdir, writeFile } from 'node:fs/promises'; // 保存接口层的实际观察结果。
const base = process.env.BASE_URL || 'http://localhost:18080'; // 默认经过真实网关与平台安全链。
async function api(path, token, body, expected = 200) { // 所有操作都通过业务 API。
  const response = await fetch(base + path, { method: body ? 'POST' : 'GET', headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: 'Bearer ' + token } : {}), 'Idempotency-Key': randomUUID() }, body: body ? JSON.stringify(body) : undefined }); // 幂等键只在提交任务时由后端使用。
  const text = await response.text(); if (response.status !== expected) throw new Error(`Expected HTTP ${expected}, got ${response.status}: ${path}`); return text ? JSON.parse(text) : {}; // 不把错误响应视为成功。
} // 结束接口助手。
async function register() { return api('/api/auth/register', '', { username: 'kb_' + randomUUID().replaceAll('-', '').slice(0, 12), password: 'knowledge-password-' + randomUUID() }); } // 临时账号不复用真实用户密码。
const owner = await register(), other = await register(); // 用户身份来自正常 JWT 签发。
const document = await api('/api/knowledge', owner.accessToken, { title: 'Knowledge isolation check', content: 'Employees receive 18 days of annual leave. Support hours are 09:00 to 18:00.' }); // 使用不含个人信息的固定文本。
const ownList = await api('/api/knowledge', owner.accessToken), otherList = await api('/api/knowledge', other.accessToken); // 分别读取两位用户的文档列表。
if (!ownList.some(item => item.id === document.id) || otherList.some(item => item.id === document.id)) throw new Error('Document list isolation failed'); // 文档不能通过列表越权泄露。
await api('/api/tasks', other.accessToken, { goal: 'Read another user document', knowledgeId: document.id, maxSteps: 3 }, 404); // 任务提交也不能引用他人的知识文档。
const quota = await api('/api/quota', other.accessToken); if (quota.reservedTasks !== 0) throw new Error('Unauthorized task reserved quota'); // 权限失败必须发生在额度预留之前。
const task = await api('/api/tasks', owner.accessToken, { goal: 'Knowledge ownership smoke check', knowledgeId: document.id, maxSteps: 3 }); // 所有者可以创建引用自己文档的任务。
const deadline = Date.now() + 60000; let completed; // 完成时间有界，不假设模型立即返回。
while (Date.now() < deadline) { completed = await api('/api/tasks/' + task.id, owner.accessToken); if (['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(completed.state)) break; await new Promise(resolve => setTimeout(resolve, 1000)); } // 仅通过状态查询观察执行。
if (completed?.state !== 'SUCCEEDED') throw new Error('Authorized knowledge task did not complete'); // 引用权限成功不代表任务必然成功。
const report = { completedAt: new Date().toISOString(), documentId: document.id, ownerCanList: true, otherCannotList: true, foreignTaskStatus: 404, foreignReservedTasks: quota.reservedTasks, authorizedTaskId: task.id, authorizedTaskState: completed.state, scope: 'ingestion, ownership and execution wiring; retrieval quality is not evaluated' }; // 明确本实验不评价模型检索效果。
await mkdir('target', { recursive: true }); await writeFile('target/knowledge-smoke-report.json', JSON.stringify(report, null, 2)); // 保存不包含令牌的证据。
console.log('PASS: knowledge ingestion, owner filtering, foreign task rejection and authorized task completion'); // 只在所有断言通过后报告成功。
