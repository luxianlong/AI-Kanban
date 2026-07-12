package com.kanban;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the Kanban backend.
 *
 * <p>Scheduling is enabled so the {@code ClaudeWorkerService} can periodically
 * poll for pending todo items and hand them to a headless Claude Code session.
 */
@SpringBootApplication
@EnableScheduling
public class KanbanApplication {

    public static void main(String[] args) {
        SpringApplication.run(KanbanApplication.class, args);
    }
}
