import { createServer } from 'node:http'; // 用协议模拟器测平台容量与故障，不能据此评价 Agent 智能。
import { randomUUID } from 'node:crypto'; // 每次调用生成独立观测标识。
const delay = Number(process.env.MOCK_DELAY_MS || 2000); // 延迟由实验控制，便于重复同一负载。
if (!Number.isFinite(delay) || delay < 0 || delay > 120000) throw new Error('MOCK_DELAY_MS must be within 0..120000'); // 防止无界模拟请求占用资源。
let inflight = 0, peak = 0, total = 0, disconnected = 0; // 这些计数只描述本模拟器的实验流量。
const server = createServer((request, response) => { // 兼容当前 Java 客户端的 OpenAI 风格接口。
  if (request.url === '/health') { response.writeHead(200); response.end('ok'); return; } // Compose 和人工探测无需认证。
  if (request.url === '/metrics') { response.writeHead(200, { 'Content-Type': 'application/json' }); response.end(JSON.stringify({ inflight, peak, total, disconnected, delayMs: delay })); return; } // 暴露可用于核对的实验计数。
  if (request.method !== 'POST' || request.url !== '/chat/completions') { response.writeHead(404); response.end(); return; } // 只实现明确的模型端点。
  let body = ''; // 不记录用户输入或请求头。
  request.on('data', chunk => { body += chunk; if (body.length > 1000000) request.destroy(); }); // 限制模拟器输入规模。
  request.on('end', () => { // 完整读取 JSON 后开始模拟耗时。
    let input; try { input = JSON.parse(body); } catch { response.writeHead(400); response.end('invalid json'); return; } // 协议错误立即失败。
    const id = randomUUID(), start = Date.now(); inflight++; total++; peak = Math.max(peak, inflight); let complete = false; // 记录真实进入模拟器的并发。
    const timer = setTimeout(() => { // 模拟上游慢调用，不忙占 CPU。
      const answer = '[MOCK] simulated completion'; // 所有输出明确标识为模拟结果。
      if (input.stream) { // 覆盖 Java 的 SSE 解析路径。
        response.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' }); // 采用标准事件分隔格式。
        response.write('data: ' + JSON.stringify({ choices: [{ delta: { content: answer } }] }) + '\n\n'); // 返回一个文本增量。
        response.write('data: ' + JSON.stringify({ choices: [], usage: { prompt_tokens: 100, completion_tokens: 20 } }) + '\n\n'); // 用量是合成数据，不能当作供应商真实账单。
        response.end('data: [DONE]\n\n'); // 显式结束流。
      } else { // 同时兼容非流式客户端。
        response.writeHead(200, { 'Content-Type': 'application/json' }); response.end(JSON.stringify({ choices: [{ message: { content: answer } }], usage: { prompt_tokens: 100, completion_tokens: 20 } })); // 保持相同人工用量。
      } // 完成协议分支。
      complete = true; console.log(JSON.stringify({ id, outcome: 'completed', durationMs: Date.now() - start })); // 不输出任务正文与密钥。
    }, delay); // 结束受控延迟配置。
    response.on('close', () => { clearTimeout(timer); inflight--; if (!complete) disconnected++; }); // 客户端取消或 Worker 退出时清理模拟工作。
  }); // 结束请求处理。
}); // 完成模拟服务构造。
server.listen(18090, '0.0.0.0', () => console.log(`MOCK model listening on 18090; delay=${delay}ms`)); // 在隔离 Compose 网络中监听，宿主机仅发布回环端口。
process.on('SIGTERM', () => server.close(() => process.exit(0))); // 容器停止时不接受新模拟请求。
