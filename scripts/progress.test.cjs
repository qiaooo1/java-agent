const { test } = require('node:test');
const assert = require('node:assert/strict');
const progress = require('../platform-service/src/main/resources/static/progress.js');
const step = (position = 1, status = 'RUNNING', extra = {}) => ({ id: `a1-step-${position}`, attemptNo: 1, position, kind: 'TOOL', title: '读取指标', status, iteration: 1, tool: 'metrics', toolAttempt: 1, errorCode: '', detail: '执行中', startedAtMillis: 1000, updatedAtMillis: 1100, elapsedMillis: 100, ...extra });
const snapshot = (extra = {}) => ({ taskId: 'task', state: 'RUNNING', attemptNo: 1, lastSeq: 2, observedAtMillis: 2000, attemptStartedAtMillis: 1000, attemptFinishedAtMillis: 0, truncated: false, incomplete: false, steps: [step()], ...extra });
const event = (seq, type, data, extra = {}) => ({ taskId: 'task', attemptNo: 1, seq, type, data: typeof data === 'object' ? JSON.stringify(data) : data, createdAt: new Date(2500).toISOString(), ...extra });

test('快照恢复按实际执行顺序排列步骤，使用服务器时间计算耗时', () => {
  const state = progress.create('task');
  assert.ok(progress.load(state, snapshot({ steps: [step(2), step(1, 'SUCCEEDED')] }), 5000));
  const view = progress.view(state, 5500);
  assert.deepEqual(view.steps.map(s => s.position), [1, 2]);
  assert.equal(view.steps[0].elapsedMillis, 100);
  assert.equal(view.steps[1].elapsedMillis, 1500);
  assert.equal(view.elapsedMillis, 1500);
});
test('工具重试更新同一行，成功后停止累计该步骤耗时', () => {
  const state = progress.create('task'); progress.load(state, snapshot(), 2000);
  progress.apply(state, event(3, 'step', step(1, 'RETRYING', { toolAttempt: 1, errorCode: 'TRANSIENT_FAILURE' })));
  assert.equal(progress.view(state, 3000).steps[0].status, 'RETRYING');
  progress.apply(state, event(4, 'step', step(1, 'SUCCEEDED', { toolAttempt: 2, updatedAtMillis: 2900 })));
  const view = progress.view(state, 8000);
  assert.equal(view.steps.length, 1); assert.equal(view.steps[0].elapsedMillis, 1900); assert.equal(view.succeeded, 1);
});
test('重复事件和迟到快照不能覆盖更新后的状态', () => {
  const state = progress.create('task'); progress.load(state, snapshot(), 2000);
  progress.apply(state, event(3, 'step', step(1, 'SUCCEEDED')));
  assert.equal(progress.apply(state, event(3, 'step', step())), false);
  assert.equal(progress.load(state, snapshot(), 2000), false);
  assert.equal(progress.view(state).steps[0].status, 'SUCCEEDED');
});
test('拒绝其他任务的数据和无效步骤，保留已知状态', () => {
  const state = progress.create('task'); progress.load(state, snapshot(), 2000);
  assert.equal(progress.load(state, snapshot({ taskId: 'other' })), false);
  assert.equal(progress.apply(state, event(3, 'done', 'FAILED', { taskId: 'other' })), false);
  progress.apply(state, event(3, 'step', '{bad-json'));
  progress.apply(state, event(4, 'step', step(2, 'RUNNING', { id: 'bad-id' })));
  assert.equal(state.incomplete, true); assert.equal(state.steps.size, 1); assert.equal(state.state, 'RUNNING');
});
test('终态关闭未结束步骤，任务和步骤耗时均冻结，不推断成功', () => {
  const state = progress.create('task'); progress.load(state, snapshot(), 2000);
  progress.apply(state, event(3, 'done', 'FAILED'));
  const first = progress.view(state, 3000), later = progress.view(state, 9000);
  assert.equal(first.steps[0].status, 'INTERRUPTED'); assert.equal(first.succeeded, 0);
  assert.equal(first.elapsedMillis, later.elapsedMillis); assert.equal(first.steps[0].elapsedMillis, later.steps[0].elapsedMillis);
});
test('取消与租约重试保留不同状态，新执行尝试不复用旧步骤', () => {
  const state = progress.create('task'); progress.load(state, snapshot(), 2000);
  progress.apply(state, event(3, 'retry', 'lease expired'));
  assert.equal(progress.view(state).steps[0].status, 'INTERRUPTED');
  progress.apply(state, event(4, 'running', '', { attemptNo: 2 }));
  progress.apply(state, event(5, 'step', step(1, 'RUNNING', { id: 'a2-step-1', attemptNo: 2 }), { attemptNo: 2 }));
  progress.apply(state, event(6, 'done', 'CANCELLED', { attemptNo: 2 }));
  assert.deepEqual(progress.view(state).steps.map(s => s.status), ['INTERRUPTED', 'CANCELLED']);
});
test('旧任务无结构化记录时保持空列表，截断标记如实保留', () => {
  const state = progress.create('task');
  progress.load(state, snapshot({ state: 'SUCCEEDED', steps: [], truncated: true, incomplete: true, attemptFinishedAtMillis: 2500 }), 3000);
  assert.equal(progress.view(state).steps.length, 0); assert.equal(state.truncated, true); assert.equal(state.incomplete, true);
});
