import http from 'node:http'; // 独立证据服务不依赖工作台数据库、Redis 或 MQ。
import { pathToFileURL } from 'node:url'; // 正确处理 Windows 与 Linux 的可执行脚本路径。
const environments = ['demo-a', 'demo-b', 'demo-c', 'demo-d']; // 公开隔离的实验快照，不读取宿主机文件。
const components = ['platform', 'worker', 'rabbitmq', 'redis']; // 查询资源固定，拒绝任意服务和命令。
const manuals = [ // 运维资料没有执行权限，只能作为解释参考。
  { id: 'RB-WORKER-1', component: 'worker', text: '任务积压且消费者为零时，先核对 Worker 实例和订阅状态；不能仅凭积压判定 Worker 故障。恢复或重启需值班人员确认。' }, // 约束单指标推断。
  { id: 'RB-BROKER-1', component: 'rabbitmq', text: 'Outbox 待发数量增长且消息连接失败时，检查 Broker 可达性和连接配置；保留未发事件，禁止手工删除积压。' }, // 对应投递路径。
  { id: 'RB-CAPACITY-1', component: 'redis', text: '消费者正常但名额达到上限时，核对运行中任务和名额租约；不要绕过限制或直接删除名额。需区分正常繁忙与泄漏。' }, // 不能自动删除协调状态。
]; // 手册版本随脚本提交保存。
function observations(environment, at) { // 使用固定可回放的合成场景，时间来自请求窗口。
  const brokerConnected = environment !== 'demo-b', consumers = environment === 'demo-a' ? 0 : 2; // 场景变化独立于模型。
  const incomplete = environment === 'demo-d'; // 专门覆盖信息不足的转交路径。
  return { // 不包含隐藏根因标签，诊断需要结合证据。
    health: { platform: 'UP', worker: consumers ? 'UP' : 'DOWN', rabbitmq: brokerConnected ? 'UP' : 'UNREACHABLE', redis: 'UP' }, // 合成组件状态。
    metrics: { queuedTasks: 16, outboxPending: brokerConnected ? 0 : 16, brokerConnected, consumers, slotsUsed: environment === 'demo-c' ? 8 : 0, slotsLimit: 8, incomplete }, // 有混淆度但可判定的指标。
    logs: [ // 每条日志都有组件和观测时间，不接受任意输入脚本。
      { at, component: 'platform', message: 'INFO tasks remain queued; investigate dispatch and execution capacity' }, // 各场景都有排队现象。
      { at, component: 'worker', message: consumers ? 'INFO consumer registered' : 'WARN no active worker heartbeat observed in demo window' }, // 只陈述观测。
      { at, component: 'rabbitmq', message: brokerConnected ? 'INFO broker connection established' : 'ERROR broker connection refused; outbox delivery pending' }, // 连接错误事实。
      { at, component: 'redis', message: environment === 'demo-c' ? 'WARN execution slots at configured limit; task deferred' : 'INFO execution slots available' }, // 容量事实。
    ], // 不包含个人信息和凭据。
  }; // 不声称发生过真实生产故障。
} // 结束可回放证据生成。
export const server = http.createServer((request, response) => { // 只提供有界只读 HTTP 接口。
  const send = (status, data) => { response.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' }); response.end(JSON.stringify(data)); }; // 输出不缓存，便于重复实验。
  const url = new URL(request.url, 'http://diagnosis-source'); // 基准域名不用于网络访问。
  if (request.method !== 'GET') return send(405, { error: 'read-only' }); // 没有故障注入或管理写接口。
  if (url.pathname === '/health') return send(200, { status: 'UP', synthetic: true }); // 证据服务健康与被观察组件状态不同。
  const match = /^\/v1\/(demo-[a-d])\/(health|metrics|logs|runbook)$/.exec(url.pathname); // 路由匹配不涉及文件系统。
  if (!match || !environments.includes(match[1])) return send(404, { error: 'unknown scope' }); // 限定资源目录。
  const [, environment, kind] = match, from = new Date(url.searchParams.get('from')), to = new Date(url.searchParams.get('to')); // 证据查询必须有时间范围。
  if (!Number.isFinite(from.getTime()) || !Number.isFinite(to.getTime()) || from >= to || to - from > 3600000) return send(400, { error: 'invalid window' }); // 与平台规则一致。
  const component = url.searchParams.get('component'), query = url.searchParams.get('q') || ''; // 查询参数只做字面匹配。
  if ((kind === 'logs' && !components.includes(component)) || query.length > 80) return send(400, { error: 'invalid query' }); // 不支持正则与任意字段选择。
  const snapshot = observations(environment, new Date((from.getTime() + to.getTime()) / 2).toISOString()); // 合成记录严格落在固定窗口内。
  let data = snapshot[kind]; // health 和 metrics 是结构化快照。
  if (kind === 'logs') data = environment === 'demo-d' ? [] : snapshot.logs.filter(row => row.component === component && row.message.toLowerCase().includes(query.toLowerCase())).slice(0, 20); // 数据不足时真实返回空结果。
  if (kind === 'runbook') data = manuals.filter(item => `${item.component} ${item.text}`.toLowerCase().includes(query.toLowerCase())).slice(0, 3); // 没有命中就返回空，不假装语义检索。
  send(200, { environment, synthetic: true, fixtureVersion: 'diagnosis-fixtures-v1', from: from.toISOString(), to: to.toISOString(), data }); // 模拟属性随每次响应传递。
}); // 单进程、固定小响应，不需要 MQ 和工作台数据库。
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) server.listen(Number(process.env.PORT || 18091), '0.0.0.0'); // 直接执行时启动，测试导入时由测试选择端口。
