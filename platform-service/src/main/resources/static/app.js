"use strict"; // 避免隐式全局变量污染页面状态。
const $ = id => document.getElementById(id); // 统一通过固定标识获取页面元素。
let accessToken = "", activeTask = "", streamAbort = null, lastSeq = 0, cursor = "", pending = null; // 访问令牌仅存内存；任务和事件有独立游标。
let pendingIncident = null; // 工单网络重试保留相同输入和幂等键。
const localMinute = date => new Date(date.getTime() - date.getTimezoneOffset() * 60000).toISOString().slice(0, 16); // datetime-local 使用浏览器本地时区。
$("incidentTo").value = localMinute(new Date()); $("incidentFrom").value = localMinute(new Date(Date.now() - 15 * 60000)); // 默认调查最近十五分钟，提交时转换为 UTC。
const states = { QUEUED: "排队中", RUNNING: "执行中", CANCEL_REQUESTED: "正在取消", RETRY_WAIT: "等待重试", SUCCEEDED: "已完成", FAILED: "失败", CANCELLED: "已取消" }; // 将状态协议转换为用户可读文本。
const terminal = state => ["SUCCEEDED", "FAILED", "CANCELLED"].includes(state); // 只在真正终态结束订阅。
const notice = message => { $("notice").textContent = message || ""; }; // 错误信息始终使用纯文本。
async function api(path, options = {}) { // 所有普通 API 请求统一添加认证与解析错误。
  const headers = { "Content-Type": "application/json", ...options.headers }; // 允许提交接口传入幂等键。
  if (accessToken) headers.Authorization = "Bearer " + accessToken; // 不把长期令牌放在 URL 或本地存储。
  const response = await fetch(path, { ...options, headers }); // 使用同源入口，默认走网关。
  const text = await response.text(); // 错误响应可能来自网关或安全链，不一定是 JSON。
  let data; try { data = text ? JSON.parse(text) : {}; } catch { data = {}; } // 非 JSON 不作为 HTML 插入页面。
  if (!response.ok) throw new Error(data.message || "请求失败，HTTP " + response.status); // 保留可解释的失败状态。
  return data; // 返回已经解析的业务对象。
} // 结束普通请求封装。
$("authForm").addEventListener("submit", async event => { // 登录和注册共用表单。
  event.preventDefault(); const button = event.submitter; button.disabled = true; // 避免相同密码哈希请求被重复提交。
  try { // 登录完成前不展示其他用户数据。
    const result = await api("/api/auth/" + button.value, { method: "POST", body: JSON.stringify({ username: $("username").value, password: $("password").value }) }); // 用户 ID 由后端签发。
    accessToken = result.accessToken; $("password").value = ""; $("auth").hidden = true; $("workspace").hidden = false; $("logout").hidden = false; // 密码立即清空。
    await Promise.all([loadTasks(true), loadKnowledge(), loadQuota()]); notice("已登录。可以提交任务或上传知识文档。"); // 并行加载互不依赖的列表。
  } catch (error) { notice(error.message); } finally { button.disabled = false; } // 失败也恢复按钮。
}); // 完成登录事件绑定。
$("logout").addEventListener("click", () => { if (streamAbort) streamAbort.abort(); accessToken = ""; location.reload(); }); // 退出清除内存令牌并关闭订阅。
async function loadQuota() { // 账户只展示任务次数，不冒充金额计费。
  const quota = await api("/api/quota"); $("quota").textContent = `${quota.period} · 已用 ${quota.usedTasks} / ${quota.limitTasks} · 预留 ${quota.reservedTasks}`; // 展示账期和在途占用。
} // 结束额度刷新。
async function loadTasks(reset = false) { // 使用后端复合游标读取历史。
  if (reset) { cursor = ""; $("tasks").replaceChildren(); } // 手动刷新从第一页重新加载。
  const tasks = await api("/api/tasks?limit=20" + (cursor ? "&before=" + encodeURIComponent(cursor) : "")); // 游标使用上一页末尾任务 ID。
  for (const task of tasks) { // 为每个任务创建安全的 DOM 节点。
    const button = document.createElement("button"), status = document.createElement("small"), title = document.createElement("span"); // 动态内容不拼 HTML。
    button.className = "task-item"; status.textContent = states[task.state] || task.state; // 状态受后端协议限制。
    try { title.textContent = JSON.parse(task.inputJson).goal; } catch { title.textContent = task.id; } // 损坏快照仍然有可定位标识。
    button.append(status, title); button.addEventListener("click", () => selectTask(task.id).catch(error => notice(error.message))); $("tasks").append(button); // 选择任务不会取消后台执行。
  } // 完成列表渲染。
  cursor = tasks.length ? tasks[tasks.length - 1].id : cursor; $("more").disabled = tasks.length < 20; // 无更多数据时禁用翻页。
} // 结束历史读取。
$("refresh").addEventListener("click", () => Promise.all([loadTasks(true), loadQuota()]).catch(error => notice(error.message))); // 刷新状态和额度。
$("more").addEventListener("click", () => loadTasks().catch(error => notice(error.message))); // 深页继续使用索引范围查询。
$("taskForm").addEventListener("submit", async event => { // 任务提交只创建持久化记录，不等待模型结果。
  event.preventDefault(); const button = event.submitter; button.disabled = true; // 仅阻止本地重复点击，真正幂等在后端。
  const body = JSON.stringify({ goal: $("goal").value.trim(), knowledgeId: $("knowledge").value || null, maxSteps: Number($("steps").value) }); // 输入快照决定幂等请求是否相同。
  if (!pending || pending.body !== body) pending = { body, key: crypto.randomUUID() }; // 网络失败后重试相同内容时复用幂等键。
  try { // 创建成功立即进入该任务的事件订阅。
    const task = await api("/api/tasks", { method: "POST", headers: { "Idempotency-Key": pending.key }, body }); // 幂等键与内容绑定。
    pending = null; await selectTask(task.id); await Promise.all([loadTasks(true), loadQuota()]); notice("任务已受理，可在历史中继续查看。"); // 已确认创建后才能清除待重试标识。
  } catch (error) { notice(error.message); } finally { button.disabled = false; } // 失败时保留幂等键。
}); // 结束提交事件绑定。
async function selectTask(id) { // 切换订阅时保持不同任务的事件序号隔离。
  if (streamAbort) streamAbort.abort(); activeTask = id; lastSeq = 0; $("events").replaceChildren(); $("answer").textContent = ""; $("diagnosisPanel").hidden = true; // 切换任务时清除前一份工单结果。
  const task = await api("/api/tasks/" + id); if (activeTask !== id) return; // 快速切换时丢弃迟到响应。
  $("taskTitle").textContent = states[task.state] || task.state; $("taskMeta").textContent = `任务 ${id} · 尝试 ${task.attemptNo} · Trace ${task.traceId}`; // 展示可用于排障的业务标识。
  $("answer").textContent = task.answer || task.error || ""; $("cancel").disabled = terminal(task.state()); // 最终答案从持久化任务读取。
  await showIncident(task); if (activeTask !== id) return; // 工单单独展示报告与人工状态。
  streamAbort = new AbortController(); void subscribe(id, streamAbort.signal).catch(error => { if (error.name !== "AbortError") notice(error.message); }); // 后台订阅不阻塞页面其他操作。
} // 结束任务选择。
async function subscribe(id, signal) { // fetch 支持 Authorization，自行实现带序号的有限重连。
  for (let attempt = 0; attempt < 4 && !signal.aborted; attempt++) { // 避免无限重连掩盖认证或服务故障。
    try { // 每次重连从最后成功处理的业务序号继续。
      const response = await fetch(`/api/tasks/${id}/stream`, { headers: { Authorization: "Bearer " + accessToken, "Last-Event-ID": String(lastSeq) }, signal }); // 令牌不进入查询字符串或日志 URL。
      if (!response.ok || !response.body) throw new Error("事件连接失败，HTTP " + response.status); // 认证和过载错误不能当作正常 SSE。
      const reader = response.body.getReader(), decoder = new TextDecoder(); let buffer = ""; // 保留分片边界之间的 UTF-8 和事件数据。
      while (!signal.aborted) { // 一次只读取一个连接的字节流。
        const chunk = await reader.read(); if (chunk.done) break; // 服务端终态或两分钟超时都会结束当前流。
        buffer = (buffer + decoder.decode(chunk.value, { stream: true })).replace(/\r\n/g, "\n"); // 同时兼容 LF 与跨分片的 CRLF。
        let boundary; while ((boundary = buffer.indexOf("\n\n")) >= 0) { // 仅处理完整事件。
          const raw = buffer.slice(0, boundary); buffer = buffer.slice(boundary + 2); // 保留不完整尾部供下一次读取。
          const data = raw.split("\n").filter(line => line.startsWith("data:")).map(line => line.slice(5).trimStart()).join("\n"); // 多行 data 按 SSE 规则拼接。
          if (data && activeTask === id) renderEvent(JSON.parse(data)); // 心跳注释无需渲染。
        } // 结束当前分片内的事件处理。
      } // 结束字节读取。
      if (signal.aborted) return; const task = await api("/api/tasks/" + id); if (activeTask !== id) return; // 断流后核对任务是否真的完成。
      $("taskTitle").textContent = states[task.state]; $("answer").textContent = task.answer || task.error || ""; $("cancel").disabled = terminal(task.state()); // 刷新最新持久化结果。
      await showIncident(task); if (activeTask !== id) return; // 报告生成后才能确认，失败工单仍可转交。
      if (terminal(task.state()) && lastSeq >= task.nextSeq) { await Promise.all([loadTasks(true), loadQuota()]); return; } // 补齐终态事件后才停止。
    } catch (error) { if (signal.aborted) return; if (attempt === 3) throw error; } // 主动切换任务不显示错误。
    await new Promise(resolve => setTimeout(resolve, Math.min(1000 * 2 ** attempt, 8000))); // 采用有上限退避，避免连接故障形成忙循环。
  } // 完成有限重连。
} // 结束流式订阅。
function renderEvent(event) { // 用业务序号防止断线重连出现重复轨迹。
  if (event.seq <= lastSeq) return; lastSeq = event.seq; // 只接受单调推进的事件。
  const row = document.createElement("div"), title = document.createElement("small"), content = document.createElement("pre"); // 全部动态文本使用安全节点。
  row.className = "event"; title.textContent = `#${event.seq} / 尝试 ${event.attemptNo} / ${event.type}`; content.textContent = event.data; // 工具参数即使包含 HTML 也只显示文本。
  row.append(title, content); $("events").append(row); $("events").scrollTop = $("events").scrollHeight; // 追加事件不清空已有工具记录。
  if (event.type === "answer") $("answer").textContent = event.data; // 最终状态仍由 task 和 done 决定。
} // 结束事件渲染。
$("cancel").addEventListener("click", async () => { // 用户点击表达请求，不假装调用立即停止。
  try { const task = await api(`/api/tasks/${activeTask}/cancel`, { method: "POST" }); $("taskTitle").textContent = states[task.state]; notice("已提交取消请求，请等待状态确认。"); } catch (error) { notice(error.message); } // 对运行中任务显示中间态。
}); // 结束取消操作。
async function loadKnowledge() { // 可选文档列表只包含自己的文档。
  const documents = await api("/api/knowledge"); $("knowledge").replaceChildren(new Option("不使用知识文档", "")); // 清除旧用户的选择项。
  for (const document of documents) $("knowledge").append(new Option(document.title, document.id)); // Option 以文本展示文档标题。
} // 结束知识列表加载。
$("knowledgeForm").addEventListener("submit", async event => { // 文本知识上传与任务提交相互独立。
  event.preventDefault(); const button = event.submitter; button.disabled = true; // 避免重复摄入同一篇文档。
  try { const result = await api("/api/knowledge", { method: "POST", body: JSON.stringify({ title: $("documentTitle").value, content: $("documentContent").value }) }); await loadKnowledge(); $("knowledge").value = result.id; notice("文档已保存，提交任务时将使用当前文档。"); } catch (error) { notice(error.message); } finally { button.disabled = false; } // 保留输入以便修正或重试。
}); // 结束知识上传绑定。
$("incidentForm").addEventListener("submit", async event => { // 工单走相同的异步可靠任务链。
  event.preventDefault(); const button = event.submitter; button.disabled = true; // 避免本地重复点击。
  try { // 时间解析错误不会创建后台任务。
    const body = JSON.stringify({ goal: $("symptom").value.trim(), knowledgeId: null, maxSteps: 8, diagnosis: { service: "demo-workbench", environment: $("environment").value, from: new Date($("incidentFrom").value).toISOString(), to: new Date($("incidentTo").value).toISOString() } }); // 授权仍由服务端验证。
    if (!pendingIncident || pendingIncident.body !== body) pendingIncident = { body, key: crypto.randomUUID() }; // 请求内容不变时复用幂等键。
    const task = await api("/api/incidents", { method: "POST", headers: { "Idempotency-Key": pendingIncident.key }, body }); // 提交后无需等模型完成。
    pendingIncident = null; await selectTask(task.id); await Promise.all([loadTasks(true), loadQuota()]); notice("工单已受理。诊断建议需要人工确认。"); // 只清除已经成功受理的请求。
  } catch (error) { notice(error.message); } finally { button.disabled = false; } // 网络错误保留重试信息。
}); // 结束工单提交绑定。
async function showIncident(task) { // 同时支持进行中、报告就绪和失败转交。
  let input; try { input = JSON.parse(task.inputJson); } catch { return; } // 旧任务损坏输入不影响列表。
  if (!input.diagnosis) return; const review = await api(`/api/incidents/${task.id}`); if (activeTask !== task.id) return; // 不让迟到响应覆盖其他任务。
  $("diagnosisPanel").hidden = false; $("diagnosisReport").replaceChildren(); $("answer").textContent = task.error || ""; // 结构化结果在专用区域展示。
  const add = (tag, value) => { const node = document.createElement(tag); node.textContent = value; $("diagnosisReport").append(node); return node; }; // 所有模型和日志文本都使用 textContent。
  if (task.answer) { // 只渲染持久化正式报告。
    try { // 无效报告只显示错误，不执行内容。
      const result = JSON.parse(task.answer), report = result.report; add("p", `${result.mode === "DEMO_SCRIPT" ? "规则演示" : "真实模型"} · 合成证据 · ${report.outcome === "NEEDS_INFORMATION" ? "需要补充信息" : "等待人工复核"}`); // 区分模型模式与证据来源。
      add("h3", report.summary); add("p", `范围：${result.scope.service} / ${result.scope.environment} / ${result.scope.from} — ${result.scope.to}`); // 说明报告适用范围。
      for (const finding of report.findings) add("p", `${finding.claim}〔${finding.evidenceIds.join("、")}〕`); // 引用与发现并列显示。
      add("h3", "处理建议"); for (const suggestion of report.suggestions) add("p", suggestion); // 建议不会被自动执行。
      add("h3", "待确认事项"); for (const unknown of report.unknowns) add("p", unknown); // 不隐藏不确定性。
      for (const evidence of result.evidence) { const details = add("details", ""), title = document.createElement("summary"), content = document.createElement("pre"); title.textContent = `${evidence.id} · ${evidence.kind} · 采集于 ${evidence.observedAt}`; content.textContent = JSON.stringify(evidence.untrustedData, null, 2); details.append(title, content); } // 用户可展开核对原始证据。
    } catch { add("p", "报告无法解析，请查看任务事件并转交处理。"); } // 不把损坏结果当有效结论。
  } else add("p", terminal(task.state) ? "调查未生成可确认的报告，可以转交人工。" : "正在收集证据，请等待调查完成。"); // 执行成功和诊断正确是两个概念。
  const labels = { PENDING: "待人工处理", CONFIRMED: "已确认阅读报告", ESCALATED: "已转交人工" }; // 不把确认描述成修复成功。
  $("reviewState").textContent = `${labels[review.state]}${review.note ? "：" + review.note : ""}`; $("reviewForm").hidden = review.state !== "PENDING"; // 历史决定只读显示。
  $("confirmReview").disabled = task.state !== "SUCCEEDED"; $("escalateReview").disabled = !terminal(task.state); // 与服务端约束一致。
} // 结束报告与人工状态渲染。
$("reviewForm").addEventListener("submit", async event => { // 确认和转交不会调用外部系统。
  event.preventDefault(); const button = event.submitter, id = activeTask; button.disabled = true; // 操作绑定点击时的任务。
  try { await api(`/api/incidents/${id}/review`, { method: "POST", body: JSON.stringify({ decision: button.value, note: $("reviewNote").value.trim() }) }); const task = await api(`/api/tasks/${id}`); if (activeTask === id) await showIncident(task); notice("人工处理决定已保存；系统未执行修复操作。"); } catch (error) { notice(error.message); } finally { button.disabled = false; } // 冲突和越权由后端拒绝。
}); // 结束人工交接绑定。
