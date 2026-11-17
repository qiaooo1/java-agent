CREATE TABLE incident_review ( -- 执行任务与人工工单状态分别持久化。
 task_id VARCHAR(36) PRIMARY KEY, -- 一个调查任务只对应一份人工处理记录。
 state VARCHAR(24) NOT NULL DEFAULT 'PENDING', -- PENDING、CONFIRMED、ESCALATED；执行成功不自动确认。
 note VARCHAR(1000) NOT NULL DEFAULT '', -- 人工确认或转交的理由，不存密钥。
 reviewed_at TIMESTAMP(6) -- 空值表示尚未经过人工处理。
); -- 与任务同事务创建，任务取消或失败仍保留该记录。
