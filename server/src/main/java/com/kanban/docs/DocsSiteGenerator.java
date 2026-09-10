package com.kanban.docs;

import com.kanban.worker.WorkerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Regenerates the static docs site (index + manifest + per-doc pages) from the
 * Markdown under {@code docs/}. This is normally a frontend {@code prebuild}
 * step; the backend re-runs it after a web-triggered delete so the index and
 * manifest immediately reflect the removed document without waiting for a full
 * redeploy.
 *
 * <p>Best-effort: a delete has already touched the filesystem by the time this
 * runs, so a regeneration failure is logged rather than surfaced as an error —
 * the next deploy regenerates everything cleanly regardless.
 */
@Component
@EnableConfigurationProperties(WorkerProperties.class)
public class DocsSiteGenerator {

    private static final Logger log = LoggerFactory.getLogger(DocsSiteGenerator.class);

    /** Repo root — the worker's working directory is this project's checkout. */
    private final Path repoRoot;

    public DocsSiteGenerator(WorkerProperties props) {
        this.repoRoot = Path.of(props.getWorkingDirectory());
    }

    /** Rebuild docs.html, docs-manifest.json and the per-doc pages from docs/. */
    public void regenerate() {
        Path script = repoRoot.resolve("scripts").resolve("build-docs-site.mjs");
        if (!Files.exists(script)) {
            log.warn("Skipping docs regeneration — generator not found at {}", script);
            return;
        }
        try {
            Process p = new ProcessBuilder("node", "scripts/build-docs-site.mjs")
                    .directory(repoRoot.toFile())
                    .redirectErrorStream(true)
                    .start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(60, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                log.warn("Docs regeneration timed out");
                return;
            }
            if (p.exitValue() != 0) {
                log.warn("Docs regeneration exited {}:\n{}", p.exitValue(), out.strip());
            } else {
                log.info("Docs site regenerated after delete");
            }
        } catch (Exception e) {
            log.warn("Docs regeneration failed", e);
        }
    }
}
