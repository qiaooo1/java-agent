-- 容量等待次数独立保存，不消耗实际执行的 attempt_no。
ALTER TABLE task ADD COLUMN capacity_deferrals INT NOT NULL DEFAULT 0;
