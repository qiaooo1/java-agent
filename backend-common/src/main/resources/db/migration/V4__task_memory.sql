-- 每个任务执行尝试有独立工作记忆；未完成工具调用不作为可恢复事实保存。
CREATE TABLE task_memory (
 task_id VARCHAR(36) NOT NULL,
 attempt_no INT NOT NULL,
 revision BIGINT NOT NULL,
 iteration INT NOT NULL,
 observation_seq INT NOT NULL,
 payload_json MEDIUMTEXT NOT NULL,
 updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(task_id, attempt_no)
);
