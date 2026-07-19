package com.kanban;

import com.kanban.docs.DocsGitCommitter;
import com.kanban.worker.WorkerProperties;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Proves the task #463 fix: a web-UI docs delete is committed to git, so the
 * still-committed source/pages can't be restored by a later
 * {@code git checkout}/{@code merge} (which is what made "deleted" docs reappear).
 *
 * <p>Runs against a real throwaway git repo. If git isn't available the test
 * self-skips rather than failing the build.
 */
class DocsGitCommitterTest {

    private DocsGitCommitter committerFor(Path repo) {
        WorkerProperties props = new WorkerProperties();
        props.setWorkingDirectory(repo.toString());
        return new DocsGitCommitter(props);
    }

    @Test
    void commitDeletionRemovesFileFromGitSoItStaysDeleted() throws Exception {
        Path repo = Files.createTempDirectory("kanban-gitcommit-test");
        assumeTrue(git(repo, "init", "-q") == 0, "git not available");
        git(repo, "config", "user.email", "test@example.com");
        git(repo, "config", "user.name", "Test");

        // Seed a doc (source .md + generated page) and commit it — the state main
        // would be in before a delete.
        Path md = repo.resolve("docs/alpha.md");
        Path page = repo.resolve("frontend/public/docs/alpha.html");
        Files.createDirectories(md.getParent());
        Files.createDirectories(page.getParent());
        Files.writeString(md, "# Alpha\n");
        Files.writeString(page, "<html>alpha</html>");
        Files.writeString(repo.resolve("frontend/public/docs-manifest.json"), "[]");
        Files.writeString(repo.resolve("frontend/public/docs.html"), "<html>index</html>");
        git(repo, "add", "-A");
        git(repo, "commit", "-q", "-m", "seed");
        assertThat(trackedFiles(repo)).contains("docs/alpha.md", "frontend/public/docs/alpha.html");

        // Simulate the controller: remove the files from disk, then persist to git.
        Files.delete(md);
        Files.delete(page);
        committerFor(repo).commitDeletion("alpha",
                List.of("docs/alpha.md", "frontend/public/docs/alpha.html"));

        // The removal is now committed — the files are gone from the tree, so a
        // `git checkout`/`merge` can no longer bring them back.
        List<String> tracked = trackedFiles(repo);
        assertThat(tracked).doesNotContain("docs/alpha.md", "frontend/public/docs/alpha.html");
        // A hard reset to the committed tree (what a checkout would restore) keeps them gone.
        git(repo, "checkout", "-q", "--", ".");
        assertThat(Files.exists(md)).isFalse();
        assertThat(Files.exists(page)).isFalse();
    }

    @Test
    void commitDeletionIsANoOpOutsideAGitRepo() throws Exception {
        // No `git init` here — a bare temp dir. Must not throw.
        Path notARepo = Files.createTempDirectory("kanban-nogit-test");
        committerFor(notARepo).commitDeletion("x", List.of("docs/x.md"));
    }

    @Test
    void commitDeletionDoesNotSweepUnrelatedChanges() throws Exception {
        Path repo = Files.createTempDirectory("kanban-gitcommit-sweep-test");
        assumeTrue(git(repo, "init", "-q") == 0, "git not available");
        git(repo, "config", "user.email", "test@example.com");
        git(repo, "config", "user.name", "Test");

        Path md = repo.resolve("docs/alpha.md");
        Path unrelated = repo.resolve("frontend/public/game/game.html");
        Files.createDirectories(md.getParent());
        Files.createDirectories(unrelated.getParent());
        Files.writeString(md, "# Alpha\n");
        Files.writeString(unrelated, "<html>v1</html>");
        Files.writeString(repo.resolve("frontend/public/docs-manifest.json"), "[]");
        Files.writeString(repo.resolve("frontend/public/docs.html"), "<html>index</html>");
        git(repo, "add", "-A");
        git(repo, "commit", "-q", "-m", "seed");

        // An unrelated, uncommitted working-tree edit that must NOT ride along.
        Files.writeString(unrelated, "<html>v2 uncommitted</html>");
        Files.delete(md);
        committerFor(repo).commitDeletion("alpha", List.of("docs/alpha.md"));

        // The delete is committed, but the unrelated edit is still uncommitted.
        assertThat(trackedFiles(repo)).doesNotContain("docs/alpha.md");
        assertThat(porcelain(repo)).contains("frontend/public/game/game.html");
    }

    // --- helpers ------------------------------------------------------------

    private List<String> trackedFiles(Path repo) throws Exception {
        return List.of(capture(repo, "git", "ls-files").split("\n"));
    }

    private String porcelain(Path repo) throws Exception {
        return capture(repo, "git", "status", "--porcelain");
    }

    private int git(Path repo, String... args) throws IOException, InterruptedException {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "git";
        System.arraycopy(args, 0, cmd, 1, args.length);
        Process p = new ProcessBuilder(cmd).directory(repo.toFile()).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        p.waitFor(30, TimeUnit.SECONDS);
        return p.exitValue();
    }

    private String capture(Path repo, String... cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).directory(repo.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        p.waitFor(30, TimeUnit.SECONDS);
        return out.strip();
    }
}
