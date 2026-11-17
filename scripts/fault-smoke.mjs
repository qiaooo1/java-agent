import { execFile } from 'node:child_process'; // 只控制当前仓库 Compose 的 Worker，不操作其他项目。
import { promisify } from 'node:util'; // 子进程以 Promise 返回，失败能中断实验。
import { fileURLToPath } from 'node:url'; // 从脚本位置确定项目根目录。
import { randomUUID } from 'node:crypto'; // 使用独立测试账号和幂等标识。
import { mkdir, writeFile } from 'node:fs/promises'; // 保存可审查的故障实验结果。
const run = promisify(execFile), root = fileURLToPath(new URL('../', import.meta.url)); // 不依赖调用者当前目录。
const base = process.env.BASE_URL || 'http://localhost:18080'; // 仅对本机开发环境执行故障注入。
if (!['localhost', '127.0.0.1', '[::1]'].includes(new URL(base).hostname) || !process.argv.includes('--allow-worker-restart')) throw new Error('仅允许本机测试，且必须显式传入 --allow-worker-restart'); // 防止误将验证脚本用于外部环境。
const metrics = await fetch('http://localhost:18090/metrics').then(response => response.json()); // 先确认使用受控模型而非真实付费供应商。
if (metrics.delayMs < 8000) throw new Error('先启用模拟器并将 MOCK_DELAY_MS 设为至少 8000'); // 保证留出确定的在途故障窗口。
let token = ''; // 临时令牌只保存在当前进程内存。
async function api(path, body, key) { // 实验经过真实网关、认证和业务接口。
  const response = await fetch(base + path, { method: body ? 'POST' : 'GET', headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: 'Bearer ' + token } : {}), ...(key ? { 'Idempotency-Key': key } : {}) }, body: body ? JSON.stringify(body) : undefined }); // 不从数据库绕过业务约束创建任务。
  const data = await response.json(); if (!response.ok) throw new Error(`HTTP ${response.status}: ${data.message || 'request failed'}`); return data; // 任何失败都应体现在最终报告中。
} // 结束请求封装。
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms)); // 有界轮询不形成忙循环。
async function waitFor(id, predicate, timeout) { // 等待明确业务条件而非固定睡眠猜测执行进度。
  const deadline = Date.now() + timeout; // 每次等待都有截止时间。
  while (Date.now() < deadline) { const task = await api('/api/tasks/' + id); if (predicate(task)) return task; await sleep(1000); } // 每秒观察一次状态。
  throw new Error('等待任务状态超时'); // 未达到条件不能输出通过。
} // 结束状态等待。
async function compose(...args) { await run('docker', ['compose', '--project-directory', root, ...args], { cwd: root, timeout: 30000 }); } // 命令参数数组不经 shell 拼接。
const auth = await api('/api/auth/register', { username: 'fault_' + randomUUID().replaceAll('-', '').slice(0, 12), password: 'fault-password-' + randomUUID() }); token = auth.accessToken; // 专属账号避免影响已有用户额度。
const task = await api('/api/tasks', { goal: '本地模拟故障恢复验证', knowledgeId: null, maxSteps: 3 }, randomUUID()); // 输入不涉及个人数据或外部写操作。
await waitFor(task.id, current => current.state === 'RUNNING', 30000); // 确认任务真正被领取后再制造失联。
const start = Date.now(); let stopped = false; // 记录恢复观察时间与清理责任。
try { // 即使故障断言失败，也要尝试重新启动 Worker。
  stopped = true; await compose('kill', 'worker'); // 先承担恢复责任，即使只终止部分实例后命令失败也要尝试重新启动。
  await compose('start', 'worker'); stopped = false; // 恢复执行容量，等待数据库租约扫描处理旧尝试。
  const completed = await waitFor(task.id, current => ['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(current.state), 120000); // 租约过期、退避和重新执行都包含在观察窗口内。
  const attempts = await api(`/api/tasks/${task.id}/attempts`), quota = await api('/api/quota'); // 核对执行历史与最终账本效果。
  if (completed.state !== 'SUCCEEDED' || completed.attemptNo < 2 || !completed.answer?.startsWith('[MOCK]')) throw new Error('任务没有通过新尝试安全恢复'); // 不接受演示短路或旧尝试伪成功。
  if (!attempts.some(attempt => attempt.state === 'LEASE_EXPIRED') || quota.usedTasks !== 1 || quota.reservedTasks !== 0) throw new Error('尝试归档或次数结算不一致'); // 故障重试只能产生一次任务次数扣减。
  const report = { completedAt: new Date().toISOString(), taskId: task.id, state: completed.state, attemptNo: completed.attemptNo, observedRecoveryMs: Date.now() - start, modelDelayMs: metrics.delayMs, attempts, quota }; // 观察时间属于本次实验，不推广为性能承诺。
  await mkdir(new URL('../target/', import.meta.url), { recursive: true }); await writeFile(new URL('../target/fault-smoke-report.json', import.meta.url), JSON.stringify(report, null, 2)); // 机器报告保存原始可核对结果。
  console.log(`PASS: task=${task.id}, attempts=${completed.attemptNo}, settlement=1, report=target/fault-smoke-report.json`); // 全部检查成功才输出通过。
} finally { if (stopped) await compose('start', 'worker'); } // 退出时不故意留下停止的执行服务。
