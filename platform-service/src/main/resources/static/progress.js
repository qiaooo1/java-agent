"use strict"; // 同一个状态归并模块供浏览器与 Node 回归测试使用。
(function (root, factory) { const api = factory(); if (typeof module === "object" && module.exports) module.exports = api; else root.IncidentProgress = api; })(globalThis, function () { // 不引入前端运行时依赖。
  const taskStates = new Set(["QUEUED", "RUNNING", "CANCEL_REQUESTED", "RETRY_WAIT", "SUCCEEDED", "FAILED", "CANCELLED"]); // 与任务协议保持一致。
  const stepStates = new Set(["RUNNING", "RETRYING", "SUCCEEDED", "FAILED", "CANCELLED", "INTERRUPTED"]), kinds = new Set(["MODEL", "TOOL", "VALIDATION"]); // 仅归并已知元数据。
  const labels = { RUNNING: "执行中", RETRYING: "等待重试", SUCCEEDED: "已完成", FAILED: "失败", CANCELLED: "已取消", INTERRUPTED: "已中断" }; // 运行中与成功必须分开。
  const terminal = state => ["SUCCEEDED", "FAILED", "CANCELLED"].includes(state); // 不把取消请求当作已停止。
  const active = step => step.status === "RUNNING" || step.status === "RETRYING"; // 运行中耗时才继续增加。
  const number = value => Number.isFinite(value) && value >= 0; // 拒绝坏时间戳与 NaN。
  function valid(step) { return step && Number.isInteger(step.attemptNo) && step.attemptNo > 0 && Number.isInteger(step.position) && step.position > 0 && step.position <= 1000 && step.id === `a${step.attemptNo}-step-${step.position}` && kinds.has(step.kind) && stepStates.has(step.status) && typeof step.title === "string" && step.title.length <= 160 && typeof step.detail === "string" && step.detail.length <= 300 && number(step.startedAtMillis) && number(step.updatedAtMillis) && step.updatedAtMillis >= step.startedAtMillis; } // 持久化与实时更新采用同一校验。
  function create(taskId) { return { taskId, state: "QUEUED", attemptNo: 0, lastSeq: 0, started: 0, finished: 0, offset: 0, updated: 0, truncated: false, incomplete: false, steps: new Map() }; } // 切换任务创建独立状态。
  function closeOpen(state, at) { // 根据持久化终态或新尝试关闭未收到完成事件的旧步骤。
    for (const [id, step] of state.steps) if (active(step) && (step.attemptNo < state.attemptNo || terminal(state.state) || state.state === "RETRY_WAIT")) { // 不能把未完成步骤永远显示为运行中。
      const end = Math.max(step.updatedAtMillis, at || step.updatedAtMillis); // 时钟差不产生负耗时。
      state.steps.set(id, { ...step, status: state.state === "CANCELLED" && step.attemptNo === state.attemptNo ? "CANCELLED" : "INTERRUPTED", updatedAtMillis: end, detail: "执行尝试已结束，未收到本步骤完成事件" }); // 不凭任务成功就推测某一步成功。
    } // 仅改变尚未结束的步骤。
  } // 结束开放步骤收尾。
  function load(state, snapshot, now = Date.now()) { // 网络迟到的旧快照不能覆盖新 SSE 事件。
    if (!snapshot || snapshot.taskId !== state.taskId || !taskStates.has(snapshot.state) || !Number.isInteger(snapshot.lastSeq) || snapshot.lastSeq < state.lastSeq) return false; // 同时隔离任务与事件水位。
    state.state = snapshot.state; state.attemptNo = snapshot.attemptNo; state.lastSeq = snapshot.lastSeq; state.started = snapshot.attemptStartedAtMillis || 0; state.finished = snapshot.attemptFinishedAtMillis || 0; state.offset = (snapshot.observedAtMillis || now) - now; state.truncated = !!snapshot.truncated; state.incomplete = !!snapshot.incomplete; state.steps.clear(); // 采用服务端已验证的快照。
    for (const step of snapshot.steps || []) { if (valid(step) && step.attemptNo <= state.attemptNo) state.steps.set(step.id, { ...step }); else state.incomplete = true; } // 错误记录不破坏整张页面。
    state.updated = Math.max(state.started, ...Array.from(state.steps.values(), step => step.updatedAtMillis)); closeOpen(state, state.finished || state.updated); return true; // 保留已知历史时刻。
  } // 结束快照装载。
  function apply(state, event) { // 所有事件序号都推进水位，重复回放不重复创建步骤。
    if (!event || event.taskId !== state.taskId || !Number.isInteger(event.seq) || event.seq <= state.lastSeq) return false; // 丢弃其他任务及旧事件。
    state.lastSeq = event.seq; const time = Date.parse(event.createdAt) || state.updated; const attempt = Number.isInteger(event.attemptNo) ? event.attemptNo : state.attemptNo; // 时间来自后端持久化事件。
    if (event.type === "running") { state.state = "RUNNING"; state.attemptNo = attempt; state.started = time; state.finished = 0; closeOpen(state, time); } // 新尝试不会和旧步骤共用执行状态。
    if (event.type === "queued") state.state = "QUEUED"; // 排队没有伪造运行步骤。
    if (event.type === "retry") { state.state = "RETRY_WAIT"; state.finished = time; closeOpen(state, time); } // 租约恢复与工具内部重试是不同层次。
    if (event.type === "cancel_requested") state.state = "CANCEL_REQUESTED"; // 等待执行端确认。
    if (event.type === "done" && terminal(event.data)) { state.state = event.data; state.finished = time; closeOpen(state, time); } // 终态立即展示，无需等 SSE 关闭。
    if (event.type === "step") { // 步骤事件携带完整状态，因此可以直接回放恢复。
      let step; try { step = typeof event.data === "string" ? JSON.parse(event.data) : event.data; } catch { state.incomplete = true; return true; } // 不解析为 HTML。
      if (!valid(step) || step.attemptNo !== attempt || step.attemptNo > state.attemptNo) { state.incomplete = true; return true; } // 拒绝跨尝试的坏记录。
      state.steps.set(step.id, { ...step }); state.updated = Math.max(state.updated, step.updatedAtMillis); closeOpen(state, state.finished || time); // 一个逻辑工具步骤只保留最新状态。
    } // 其他事件保留原始轨迹用途。
    return true; // 表示水位确实推进。
  } // 结束实时事件归并。
  function view(state, now = Date.now()) { // 展示耗时仅随真实时钟变化，不制造进度百分比。
    const serverNow = now + state.offset, steps = Array.from(state.steps.values()).sort((a, b) => a.attemptNo - b.attemptNo || a.position - b.position).map(step => ({ ...step, label: labels[step.status], elapsedMillis: Math.max(0, (active(step) ? serverNow : step.updatedAtMillis) - step.startedAtMillis) })); // 历史耗时保持冻结。
    const current = steps.find(step => step.attemptNo === state.attemptNo && active(step)); // 当前执行尝试最多一个串行活跃步骤。
    const stage = state.state === "QUEUED" ? "排队等待 Worker" : state.state === "RETRY_WAIT" ? "等待重新调度" : state.state === "CANCEL_REQUESTED" ? "取消请求已提交，等待执行端确认" : state.state === "SUCCEEDED" ? "任务已完成" : state.state === "FAILED" ? "任务失败，请查看原因" : state.state === "CANCELLED" ? "任务已取消" : current ? `${current.status === "RETRYING" ? "正在重试：" : "正在执行："}${current.title}` : "等待下一执行步骤或结果结算"; // 只依据可观察事实生成说明。
    const end = terminal(state.state) || state.state === "RETRY_WAIT" ? state.finished || state.updated : serverNow; // 执行结束后总耗时停止增长。
    return { stage, steps, elapsedMillis: state.started ? Math.max(0, end - state.started) : null, succeeded: steps.filter(step => step.status === "SUCCEEDED").length, failed: steps.filter(step => step.status === "FAILED").length, lastChangeMillis: state.updated ? Math.max(0, serverNow - state.updated) : null }; // 完成与失败数量不冒充总任务百分比。
  } // 结束展示投影。
  return { create, load, apply, view }; // 纯状态模块无需 DOM，便于覆盖重连、乱序与隔离测试。
}); // 完成浏览器及 Node 双端装配。
