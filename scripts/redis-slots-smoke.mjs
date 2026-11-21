// 使用独立、无持久化、仅监听回环地址的 Redis 运行 Java Lua 并发测试。
import { spawn } from 'node:child_process';
import { createWriteStream } from 'node:fs';
import { mkdir, readFile } from 'node:fs/promises';
import net from 'node:net';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
const root = fileURLToPath(new URL('../', import.meta.url)), args = process.argv.slice(2);
const index = args.indexOf('--redis-server'), executable = index >= 0 ? args[index + 1] : process.env.REDIS_SERVER || 'redis-server';
if (!executable) throw new Error('--redis-server 需要可执行文件路径');
const dir = path.join(root, 'target', 'redis-slots-smoke'); await mkdir(dir, { recursive: true });
const reservation = net.createServer(); await new Promise((resolve, reject) => { reservation.once('error', reject); reservation.listen(0, '127.0.0.1', resolve); });
const port = reservation.address().port; await new Promise(resolve => reservation.close(resolve));
let redisError, ready = false;
const serverLog = createWriteStream(path.join(dir, 'redis.log'));
const server = spawn(executable, ['--bind', '127.0.0.1', '--port', String(port), '--protected-mode', 'yes', '--save', '', '--appendonly', 'no', '--dir', dir], { cwd: root, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
server.on('error', error => { redisError = error; });
server.stdout.on('data', chunk => { if (/ready to accept connections/i.test(chunk.toString())) ready = true; });
server.stdout.pipe(serverLog, { end: false }); server.stderr.pipe(serverLog, { end: false });
try {
  const began = Date.now();
  while (!ready) {
    if (redisError) throw redisError;
    if (server.exitCode !== null) throw new Error(`临时 Redis 退出：${server.exitCode}，参见 ${dir}`);
    if (Date.now() - began > 15000) throw new Error(`临时 Redis 未就绪，参见 ${dir}`);
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  const mavenArgs = [...(args.includes('--offline') ? ['-o'] : []), '-B', '-ntp', '-pl', 'backend-common', '-am', 'test', '-Dtest=RedisSlotsIT', `-Dslots.test.port=${port}`, '-Dsurefire.failIfNoSpecifiedTests=false'];
  const logPath = path.join(dir, 'maven.log'), log = createWriteStream(logPath);
  // Windows 的 mvn 是 cmd 脚本；命令参数全部由固定选项和服务端生成的数字端口组成。
  const maven = process.platform === 'win32'
    ? spawn('cmd.exe', ['/d', '/s', '/c', 'mvn ' + mavenArgs.join(' ')], { cwd: root, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] })
    : spawn('mvn', mavenArgs, { cwd: root, stdio: ['ignore', 'pipe', 'pipe'] });
  maven.stdout.pipe(log, { end: false }); maven.stderr.pipe(log, { end: false });
  const code = await new Promise((resolve, reject) => { maven.once('error', reject); maven.once('close', resolve); });
  await new Promise(resolve => log.end(resolve));
  console.log((await readFile(logPath, 'utf8')).split(/\r?\n/).slice(-24).join('\n'));
  if (code !== 0) throw new Error(`Redis Lua 测试失败，参见 ${logPath}`);
  console.log(`独立 Redis 上的并发 Lua 测试通过；日志目录：${dir}`);
} finally {
  // 仅停止本脚本启动的子进程，不操作已有 Redis，也不删除目录。
  if (server.exitCode === null && server.pid) { const exited = new Promise(resolve => server.once('exit', resolve)); server.kill(); await exited; }
  await new Promise(resolve => serverLog.end(resolve));
}
