CREATE TABLE app_user ( -- 用户身份与密码摘要，仅平台提供写入口。
 id VARCHAR(36) PRIMARY KEY, -- 用户标识由服务端生成。
 username VARCHAR(64) NOT NULL UNIQUE, -- 唯一索引保护并发注册。
 password_hash VARCHAR(100) NOT NULL, -- BCrypt 摘要，禁止保存明文密码。
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) -- 创建时间由数据库给出。
); -- 结束用户表。
CREATE TABLE quota_account ( -- 自然月任务次数账户，事务锁定此行进行预留。
 user_id VARCHAR(36) NOT NULL, -- 额度属于认证用户。
 billing_period VARCHAR(7) NOT NULL, -- 任务提交时固定账期，跨月不改变归属。
 limit_tasks INT NOT NULL, -- 允许完成的任务次数上限。
 used_tasks INT NOT NULL DEFAULT 0, -- 成功任务累计计数。
 reserved_tasks INT NOT NULL DEFAULT 0, -- 已提交但未结算的预留次数。
 PRIMARY KEY (user_id, billing_period), -- 同一用户每月只有一个账户。
 CHECK (used_tasks >= 0 AND reserved_tasks >= 0) -- 防止释放或重复结算产生负数。
); -- 结束额度账户。
CREATE TABLE task ( -- 任务是调度与账本之间的业务锚点。
 id VARCHAR(36) PRIMARY KEY, -- 对外任务标识。
 user_id VARCHAR(36) NOT NULL, -- 所有查询必须带用户范围。
 request_key VARCHAR(80) NOT NULL, -- 客户端稳定重试时复用相同幂等键。
 input_json TEXT NOT NULL, -- 保存目标和 Agent 参数快照。
 agent_config_json TEXT NOT NULL, -- 固定模型、输出上限和安全工具集版本，重试不能漂移配置。
 state VARCHAR(24) NOT NULL, -- 明确区分排队、执行、取消请求和终态。
 attempt_no INT NOT NULL DEFAULT 0, -- 单调递增，作为 fencing token。
 max_attempts INT NOT NULL DEFAULT 3, -- 限制故障恢复次数。
 worker_id VARCHAR(100), -- 当前执行者实例标识。
 lease_until TIMESTAMP(6), -- 使用数据库时钟计算租约。
 available_at TIMESTAMP(6) NOT NULL, -- 重试退避截止时间。
 cancel_requested BOOLEAN NOT NULL DEFAULT FALSE, -- 用户仅请求取消，由执行端确认在途取消。
 answer TEXT, -- 最终结果以数据库为准。
 error TEXT, -- 保留可向用户展示的故障原因。
 billing_period VARCHAR(7) NOT NULL, -- 归属提交时的 UTC 自然月。
 trace_id VARCHAR(36) NOT NULL, -- 从 HTTP 贯穿消息、事件与 Worker 日志。
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), -- 游标分页的时间排序字段。
 next_seq INT NOT NULL DEFAULT 0, -- 每任务事件序号在持有任务行锁时递增。
 UNIQUE (user_id, request_key) -- 防止重复提交创建多条任务和重复预留。
); -- 结束任务表。
CREATE INDEX idx_task_user_cursor ON task(user_id, created_at, id); -- 支持用户范围内稳定游标分页。
CREATE INDEX idx_task_recovery ON task(state, lease_until); -- 恢复器只扫描执行中且租约过期的任务。
CREATE TABLE task_attempt ( -- 每次执行单独记录，不能被新尝试覆盖。
 task_id VARCHAR(36) NOT NULL, -- 所属任务。
 attempt_no INT NOT NULL, -- 尝试编号。
 worker_id VARCHAR(100) NOT NULL, -- 执行实例。
 state VARCHAR(24) NOT NULL, -- RUNNING、终态或 LEASE_EXPIRED。
 prompt_tokens INT NOT NULL DEFAULT 0, -- 已返回的输入用量。
 completion_tokens INT NOT NULL DEFAULT 0, -- 已返回的输出用量。
 started_at TIMESTAMP(6) NOT NULL, -- 开始时间。
 finished_at TIMESTAMP(6), -- 结束或失联时间。
 PRIMARY KEY(task_id, attempt_no) -- 重复消息不能创建相同执行尝试。
); -- 结束尝试表。
CREATE TABLE usage_ledger ( -- 任务次数结算账本，不宣称金额计费。
 task_id VARCHAR(36) PRIMARY KEY, -- 每个任务仅允许一次最终结算。
 attempt_no INT NOT NULL, -- 记录产生最终结算的尝试。
 user_id VARCHAR(36) NOT NULL, -- 便于用户核对账本。
 billing_period VARCHAR(7) NOT NULL, -- 使用任务固定账期。
 charged_tasks INT NOT NULL, -- 成功扣一次，失败或取消释放预留。
 outcome VARCHAR(24) NOT NULL, -- 保存结算时终态以识别重复冲突。
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) -- 数据库记录结算时间。
); -- 结束结算账本。
CREATE TABLE task_event ( -- 可补读的事件事实来源，不依赖缓存存活。
 task_id VARCHAR(36) NOT NULL, -- 按任务隔离事件。
 seq INT NOT NULL, -- 每任务连续递增的业务序号。
 attempt_no INT NOT NULL, -- 防止混淆不同执行尝试。
 event_type VARCHAR(32) NOT NULL, -- queued、iteration、action、observation、answer、done 等。
 event_data TEXT NOT NULL, -- 文本片段合并后写入，降低数据库压力。
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), -- 事件持久化时间。
 PRIMARY KEY(task_id, seq) -- 按序补读天然利用主键索引。
); -- 结束事件表。
CREATE TABLE outbox_event ( -- 与任务创建、重试安排使用同一事务落库。
 id VARCHAR(36) PRIMARY KEY, -- 每次调度通知的独立标识。
 task_id VARCHAR(36) NOT NULL, -- 待执行任务。
 trace_id VARCHAR(36) NOT NULL, -- 贯穿消息追踪。
 state VARCHAR(16) NOT NULL DEFAULT 'PENDING', -- PENDING、SENDING、SENT。
 owner VARCHAR(36), -- 发布租约的唯一持有者。
 lease_until TIMESTAMP(6), -- 发布器崩溃后其他实例可回收。
 available_at TIMESTAMP(6) NOT NULL, -- 重试退避与任务可执行时间一致。
 failures INT NOT NULL DEFAULT 0, -- 发布失败次数供观测。
 last_error VARCHAR(500), -- 最后一次失败原因，便于排障。
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) -- 创建时间。
); -- 结束 Outbox 表。
CREATE INDEX idx_outbox_ready ON outbox_event(state, available_at, lease_until); -- 避免发布器全表扫描。
CREATE TABLE knowledge_document ( -- 文档属于用户，任务仅允许引用自己的文档。
 id VARCHAR(36) PRIMARY KEY, -- 文档标识。
 user_id VARCHAR(36) NOT NULL, -- 租户隔离条件。
 title VARCHAR(120) NOT NULL, -- 文档显示名称。
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) -- 上传时间。
); -- 结束文档表。
CREATE INDEX idx_document_owner ON knowledge_document(user_id, created_at); -- 用户文档列表索引。
CREATE TABLE knowledge_chunk ( -- 第一版小规模检索使用本地特征哈希向量。
 document_id VARCHAR(36) NOT NULL, -- 所属文档。
 chunk_no INT NOT NULL, -- 文档内片段顺序。
 content TEXT NOT NULL, -- 原始片段用于引用。
 vector_json TEXT NOT NULL, -- JSON 向量快照，替换模型时应重建索引。
 PRIMARY KEY(document_id, chunk_no) -- 限定单文档检索的扫描范围。
); -- 结束知识片段表。
