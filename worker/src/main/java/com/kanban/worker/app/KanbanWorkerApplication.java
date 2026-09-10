package com.kanban.worker.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point of the worker service.
 *
 * <p>Not a web application: the worker only ever makes outbound calls to the server's agent
 * API, which is what lets it run on a laptop behind NAT or anywhere else the board could not
 * possibly reach. It is a Spring Boot app all the same, for configuration binding, the
 * scheduler that drives the claim loop, and a managed lifecycle — the last of which matters,
 * because shutting down cleanly is how a running session gets killed and its lease handed back
 * instead of left to expire.
 *
 * <p>Run one per machine you want doing work; run several on the same machine if you want more
 * at once. They need no coordination, because the server hands each unit out exactly once.
 *
 * <pre>
 *   java -jar kanban-worker.jar \
 *     --worker.server-url=https://youraikanban.com \
 *     --worker.token=$KANBAN_WORKER_TOKEN \
 *     --worker.name=laptop-01 \
 *     --worker.capabilities=general,workflow
 * </pre>
 */
@SpringBootApplication(scanBasePackages = "com.kanban.worker")
@EnableScheduling
public class KanbanWorkerApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(KanbanWorkerApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.run(args);
    }
}
