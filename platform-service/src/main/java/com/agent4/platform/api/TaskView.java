package com.agent4.platform.api;

import com.agent4.contract.Contracts.Task;
import java.time.Instant;

/** 用户侧响应显式列出可公开字段，内部调度快照继续仅供服务端使用。 */
public record TaskView(String id, String inputJson, String state, int attemptNo,
                       boolean cancelRequested, String answer, String error,
                       String billingPeriod, String traceId, Instant createdAt, int nextSeq) {
    static TaskView from(Task task) {
        return new TaskView(task.id(), task.inputJson(), task.state(), task.attemptNo(),
                task.cancelRequested(), task.answer(), task.error(), task.billingPeriod(),
                task.traceId(), task.createdAt(), task.nextSeq());
    }
}
