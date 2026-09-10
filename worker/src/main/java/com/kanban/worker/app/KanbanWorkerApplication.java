package com.kanban.worker.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point of the worker service.
 *
 * <p>Not a web application: the worker only ever makes outbound calls to the server's
 * agent API, which is what lets it run on a laptop behind NAT. It is a Spring Boot app
 * all the same, for configuration binding, the scheduler and a managed lifecycle.
 *
 * <p>The claim/run/report loop arrives in a later phase; today this starts and idles, so
 * the module is part of the build and the packaging is proven before anything depends
 * on it.
 */
@SpringBootApplication
public class KanbanWorkerApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(KanbanWorkerApplication.class);
        app.setWebApplicationType(org.springframework.boot.WebApplicationType.NONE);
        app.run(args);
    }
}
