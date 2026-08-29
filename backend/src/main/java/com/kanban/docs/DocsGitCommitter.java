package com.kanban.docs;

import com.kanban.worker.WorkerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Persists a web-triggered docs delete into git so it survives the next deploy.
 *
 * <p>Why this exists (task #463 — "为什么删掉的doc又出来了", why does a deleted doc come
 * back): the docs sources ({@code docs/*.md}, {@code research/<dir>/**}), their
 * generated pages ({@code frontend/public/docs/*.html}, bespoke root pages) and
 * the index ({@code docs.html} + {@code docs-manifest.json}) are all committed
 * to git. {@link DocsController#delete} removes those files from the working
 * tree only — it never touches git. So the next {@code deploy.sh}, which runs
 * {@code git checkout main} + {@code git merge}, restores every still-committed
 * file from the tree, and the frontend prebuild regenerates the pages from the
 * restored Markdown: the "deleted" doc reappears. (Same class of bug as the DB
 * clobber in task #270 — a web-UI mutation of a git-tracked file that a later
 * git checkout/merge silently rolls back.)
 *
 * <p>The fix is to commit the removal right after the delete, so the doc is gone
 * from the committed tree too and no checkout/merge can bring it back.
 * Best-effort: the files are already gone on disk by the time this runs, so a
 * git failure is logged, not surfaced — the delete still succeeds (it just risks
 * the old resurrection until the next deploy, i.e. exactly today's behaviour).
 */
@Component
@EnableConfigurationProperties(WorkerProperties.class)
public class DocsGitCommitter {

    private static final Logger log = LoggerFactory.getLogger(DocsGitCommitter.class);

    /** Index files the site regeneration rewrites; committed alongside the removals. */
    private static final List<String> INDEX_FILES =
            List.of("frontend/public/docs.html", "frontend/public/docs-manifest.json");

    /** Repo root — the worker's working directory is this project's checkout. */
    private final Path repoRoot;

    public DocsGitCommitter(WorkerProperties props) {
        this.repoRoot = Path.of(props.getWorkingDirectory());
    }

    /**
     * Stage the just-deleted paths (plus the regenerated index) and commit them,
     * so a later deploy's {@code git checkout}/{@code merge} can't restore the
     * doc. No-op when the working directory isn't a git repo or nothing is staged
     * (e.g. the doc was already untracked).
     *
     * @param id           the manifest id being deleted (for the commit message/logs)
     * @param deletedPaths the repo-relative paths that were removed from disk
     */
    public void commitDeletion(String id, List<String> deletedPaths) {
        commitPaths("docs: delete '" + id + "' via web UI", deletedPaths);
    }

    /**
     * Stage the given paths (plus the regenerated index) and commit them under
     * {@code message}. Used for any web-triggered change to the docs tree — a
     * delete, or a page published through the public upload API (task #470),
     * which faces the same resurrection/rollback trap. No-op when the working
     * directory isn't a git repo or nothing ends up staged.
     *
     * @param message the commit message
     * @param paths   the repo-relative paths that were added or removed on disk
     */
    public void commitPaths(String message, List<String> paths) {
        try {
            if (!isGitRepo()) {
                log.info("Skipping git commit of docs change — {} is not a git work tree", repoRoot);
                return;
            }
            // Stage ONLY the affected paths + the regenerated index — never a bare
            // `git add -A`, which would sweep unrelated working-tree changes (e.g.
            // an in-flight edit elsewhere in the repo) into the commit.
            List<String> add = new ArrayList<>(List.of("git", "add", "-A", "--"));
            if (paths != null) {
                add.addAll(paths);
            }
            add.addAll(INDEX_FILES);
            if (run(add) != 0) {
                return; // run() already logged the failure
            }
            // Nothing staged (e.g. the files were already absent from git) -> no commit.
            if (run(List.of("git", "diff", "--cached", "--quiet")) == 0) {
                log.info("No git changes to commit for: {}", message);
                return;
            }
            if (run(List.of("git", "commit", "--no-verify", "-m", message)) == 0) {
                log.info("Committed to git: {}", message);
            }
        } catch (Exception e) {
            log.warn("Failed to commit docs change to git ({}) — already applied on disk", message, e);
        }
    }

    private boolean isGitRepo() throws Exception {
        return run(List.of("git", "rev-parse", "--is-inside-work-tree")) == 0;
    }

    /** Run a git command in the repo root, returning its exit code (or -1 on error/timeout). */
    private int run(List<String> cmd) throws Exception {
        Process p = new ProcessBuilder(cmd)
                .directory(repoRoot.toFile())
                .redirectErrorStream(true)
                .start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(30, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            log.warn("git {} timed out", cmd);
            return -1;
        }
        int code = p.exitValue();
        // `git diff --cached --quiet` and `rev-parse` use their exit code as a
        // boolean answer, so a non-zero there isn't an error worth logging.
        if (code != 0 && !cmd.contains("diff") && !cmd.contains("rev-parse")) {
            log.warn("git {} exited {}:\n{}", cmd, code, out.strip());
        }
        return code;
    }
}
