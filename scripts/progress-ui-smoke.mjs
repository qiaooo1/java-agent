// 浏览器测试使用本地固定 API / SSE；不调用模型，不依赖已部署服务。
import http from 'node:http';
import { readFile, mkdir, writeFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import assert from 'node:assert/strict';
const root = fileURLToPath(new URL('../', import.meta.url)), require = createRequire(import.meta.url);
let chromium;
try { ({ chromium } = require('playwright')); }
catch { ({ chromium } = require('../target/progress-browser-tools/node_modules/playwright')); }
const output = path.join(root, 'target', 'progress-ui'); await mkdir(output, { recursive: true });
const start = Date.now() - 3000, events = [], steps = new Map(), streams = new Set(), resumes = [];
let state = 'RUNNING', finished = 0;
const task = id => ({ id, state: id === 'other' ? 'QUEUED' : state, attemptNo: id === 'other' ? 0 : 1, nextSeq: id === 'other' ? 0 : events.length, inputJson: JSON.stringify({ goal: id === 'other' ? '其他任务' : '排查队列积压' }), traceId: 'fixture', answer: state === 'SUCCEEDED' ? '测试完成' : '' });
const step = (position, kind, status, extra = {}) => ({ id: `a1-step-${position}`, attemptNo: 1, position, kind, title: { MODEL: '第 1 轮模型分析', TOOL: '读取服务指标', VALIDATION: '校验任务结果' }[kind], status, iteration: 1, tool: kind === 'TOOL' ? 'metrics' : '', toolAttempt: kind === 'TOOL' ? 1 : 0, errorCode: '', detail: '处理中', startedAtMillis: start, updatedAtMillis: Date.now(), elapsedMillis: 0, ...extra });
const frame = e => `id: ${e.seq}\nevent: ${e.type}\ndata: ${JSON.stringify(e)}\n\n`;
function emit(type, data) {
  if (type === 'step') steps.set(data.id, data);
  if (type === 'done') { state = data; finished = Date.now(); }
  const e = { taskId: 'task', attemptNo: 1, seq: events.length + 1, type, data: typeof data === 'object' ? JSON.stringify(data) : data, createdAt: new Date().toISOString() };
  events.push(e); for (const response of streams) response.write(frame(e)); return e;
}
emit('running', ''); emit('step', step(1, 'MODEL', 'RUNNING'));
const server = http.createServer(async (request, response) => {
  try {
    const url = new URL(request.url, 'http://localhost');
    const staticFiles = { '/': 'index.html', '/app.js': 'app.js', '/progress.js': 'progress.js', '/styles.css': 'styles.css' };
    if (staticFiles[url.pathname]) { response.setHeader('Content-Type', url.pathname.endsWith('.js') ? 'text/javascript; charset=utf-8' : url.pathname.endsWith('.css') ? 'text/css; charset=utf-8' : 'text/html; charset=utf-8'); response.end(await readFile(path.join(root, 'platform-service/src/main/resources/static', staticFiles[url.pathname]))); return; }
    const route = url.pathname.match(/^\/api\/tasks\/(task|other)(?:\/(progress|stream))?$/);
    if (route?.[2] === 'stream') {
      const last = Number(request.headers['last-event-id'] || 0); resumes.push(last);
      response.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' }); response.flushHeaders();
      if (route[1] === 'other') { response.end(); return; }
      // 故意包含一个重复帧，验证断线补发不重复生成步骤。
      for (const e of events.filter(e => e.seq >= Math.max(1, last))) response.write(frame(e));
      if (state === 'SUCCEEDED') response.end(); else { streams.add(response); response.on('close', () => streams.delete(response)); } return;
    }
    let data;
    if (url.pathname === '/api/auth/login') data = { accessToken: 'local-fixture' };
    else if (url.pathname === '/api/tasks') data = [task('task'), task('other')];
    else if (url.pathname === '/api/knowledge') data = [];
    else if (url.pathname === '/api/quota') data = { period: '测试', usedTasks: 1, limitTasks: 20, reservedTasks: 0 };
    else if (route?.[2] === 'progress') data = { taskId: route[1], state: task(route[1]).state, attemptNo: task(route[1]).attemptNo, lastSeq: task(route[1]).nextSeq, observedAtMillis: Date.now(), attemptStartedAtMillis: route[1] === 'task' ? start : 0, attemptFinishedAtMillis: finished, truncated: false, incomplete: false, steps: route[1] === 'task' ? [...steps.values()] : [] };
    else if (route) data = task(route[1]);
    else { response.writeHead(404); response.end(); return; }
    response.setHeader('Content-Type', 'application/json'); response.end(JSON.stringify(data));
  } catch (error) { response.writeHead(500); response.end(error.message); }
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const executablePath = process.env.PROGRESS_BROWSER || ['C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe', 'C:/Program Files/Google/Chrome/Application/chrome.exe'].find(existsSync);
let browser;
try {
  browser = await chromium.launch({ headless: true, ...(executablePath ? { executablePath } : {}) });
  const page = await browser.newPage({ viewport: { width: 1365, height: 1000 } }), errors = [];
  page.on('pageerror', error => errors.push(error.message));
  const base = `http://127.0.0.1:${server.address().port}`;
  const login = async () => { await page.goto(base); await page.locator('#username').fill('fixture'); await page.locator('#password').fill('password123'); await page.locator('button[value="login"]').click(); await page.locator('#tasks button').first().waitFor(); };
  await login(); await page.locator('#tasks button').first().click();
  await page.locator('.execution-step[data-status="RUNNING"]').waitFor();
  await page.waitForFunction(() => document.querySelector('#progressConnection').textContent.includes('已连接'));
  emit('step', step(1, 'MODEL', 'SUCCEEDED')); emit('step', step(2, 'TOOL', 'RUNNING'));
  await page.locator('[data-step-id="a1-step-2"][data-status="RUNNING"]').waitFor();
  emit('step', step(2, 'TOOL', 'RETRYING', { errorCode: 'TRANSIENT_FAILURE', detail: '暂时不可用，等待下一次安全重试' }));
  await page.locator('[data-step-id="a1-step-2"][data-status="RETRYING"]').waitFor();
  assert.equal(await page.locator('.execution-step').count(), 2);
  for (const response of streams) response.end();
  await page.waitForFunction(() => document.querySelector('#progressConnection').textContent.includes('已连接'));
  // 等待实际发生第二次连接，而非读取断流前的 DOM 状态。
  await new Promise((resolve, reject) => { const began = Date.now(); const check = () => resumes.length >= 2 ? resolve() : Date.now() - began > 10000 ? reject(new Error('没有发生断线恢复')) : setTimeout(check, 50); check(); });
  assert.ok(resumes[1] >= 5, `断线恢复水位错误: ${resumes}`);
  emit('step', step(2, 'TOOL', 'SUCCEEDED', { toolAttempt: 2, detail: '<img src=x onerror="window.injected=true">指标已返回' }));
  emit('step', step(3, 'VALIDATION', 'RUNNING'));
  await page.locator('[data-step-id="a1-step-3"][data-status="RUNNING"]').waitFor();
  await page.screenshot({ path: path.join(output, 'desktop.png'), fullPage: true });
  emit('step', step(3, 'VALIDATION', 'SUCCEEDED')); emit('done', 'SUCCEEDED'); for (const response of streams) response.end();
  await page.waitForFunction(() => document.querySelector('#progressConnection').textContent.includes('事件已补齐'));
  assert.equal(await page.locator('.execution-step[data-status="SUCCEEDED"]').count(), 3);
  assert.equal(await page.locator('#executionSteps img').count(), 0); assert.equal(await page.evaluate(() => window.injected), undefined);
  const endedConnections = resumes.length; await page.waitForTimeout(1500); assert.equal(resumes.length, endedConnections, '终态不应反复重连');
  const elapsed = await page.locator('#progressElapsed').textContent(); await page.waitForTimeout(1100); assert.equal(await page.locator('#progressElapsed').textContent(), elapsed);
  await login(); await page.locator('#tasks button').first().click();
  await page.waitForFunction(() => document.querySelector('#progressConnection').textContent.includes('事件已补齐'));
  assert.equal(await page.locator('.execution-step[data-status="SUCCEEDED"]').count(), 3, '刷新后应恢复步骤');
  await page.setViewportSize({ width: 390, height: 844 }); await page.screenshot({ path: path.join(output, 'mobile.png'), fullPage: true });
  assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), '移动端不应横向溢出');
  await page.locator('#tasks button').nth(1).click();
  await page.waitForFunction(() => document.querySelector('#progressStage').textContent.includes('排队'));
  assert.equal(await page.locator('.execution-step').count(), 0, '切换任务应清空旧步骤');
  assert.deepEqual(errors, []);
  await writeFile(path.join(output, 'result.json'), JSON.stringify({ passed: true, checks: ['实时步骤', '同一步骤重试', 'Last-Event-ID重连与去重', '终态停止重连及计时', '刷新恢复', '任务切换', '纯文本防注入', '移动布局'], resumes }, null, 2));
  console.log('进度浏览器测试通过：实时步骤、重试、断线恢复、终态、刷新、切换、安全文本及移动布局。');
} finally { if (browser) await browser.close(); for (const response of streams) response.end(); server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); }
