package com.agent4.platform.api;

import com.agent4.backend.TaskMemoryStore;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
public class TaskMemoryController {
    private final TaskMemoryStore memory;
    public TaskMemoryController(TaskMemoryStore memory) { this.memory = memory; }
    @GetMapping("/api/tasks/{id}/memory")
    public TaskMemoryStore.View memory(@AuthenticationPrincipal Jwt user, @PathVariable String id, @RequestParam(required = false) Integer attempt) {
        return memory.view(user.getSubject(), id, attempt);
    }
}
