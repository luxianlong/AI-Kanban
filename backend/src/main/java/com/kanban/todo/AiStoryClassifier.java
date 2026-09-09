package com.kanban.todo;

import com.kanban.worker.WorkerProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The smart, second-pass story classifier. The fast {@link StoryClassifier}
 * matches on shared literal keywords, which misses anything semantic or
 * non-Latin (e.g. two Chinese tasks that clearly belong together but share no
 * ASCII word — its tokenizer drops all CJK characters). When a freshly created
 * task lands standalone, {@link TodoController} hands it here: a short headless
 * Claude call reads the task and the existing stories and decides which one, if
 * any, it belongs to, then re-files it.
 *
 * <p>It runs <em>asynchronously</em> so task creation returns instantly — the
 * task is held ({@code classifying}) out of the worker queue and, a few seconds
 * later, hops into a story if Claude finds a home for it, or is released to run
 * standalone (the frontend polls, so it just moves). It only acts on a task still
 * in that held state, so it never yanks one already filed, released, or started.
 */
@Service
public class AiStoryClassifier {

    private static final Logger log = LoggerFactory.getLogger(AiStoryClassifier.class);

    /** Cap on step titles fed into the prompt per story, to keep it small. */
    private static final int MAX_TITLES_PER_STORY = 8;

    private final TodoRepository repository;
    private final StoryClassifier classifier;
    private final WorkerProperties props;
    // A tiny pool: classification is I/O-bound (waiting on the CLI) and low-volume.
    private final ExecutorService pool = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "ai-classify");
        t.setDaemon(true);
        return t;
    });

    public AiStoryClassifier(TodoRepository repository, StoryClassifier classifier, WorkerProperties props) {
        this.repository = repository;
        this.classifier = classifier;
        this.props = props;
    }

    @PreDestroy
    void shutdown() {
        pool.shutdownNow();
    }

    /** Whether the smart pass is active (needs the worker CLI to be enabled). */
    public boolean isEnabled() {
        return props.isEnabled() && props.isClassifyWithClaude();
    }

    /** Queue a background attempt to file todo {@code todoId} under a story. */
    public void fileAsync(Long todoId, String text) {
        if (!isEnabled()) {
            return;
        }
        pool.submit(() -> {
            try {
                file(todoId, text);
            } catch (Exception e) {
                log.warn("AI classify failed for todo #{}: {}", todoId, e.toString());
                releaseStandalone(todoId); // fail open: never leave a task held
            }
        });
    }

    /**
     * Fail-safe used when classification errors out: a task we're holding for
     * classification must still be allowed to run, so clear the {@code classifying}
     * hold if it's still set and unfiled (the task is already PENDING). Best-effort
     * — swallow any secondary failure.
     */
    private void releaseStandalone(Long todoId) {
        try {
            Todo todo = repository.findById(todoId).orElse(null);
            if (todo != null && todo.isClassifying() && todo.getStoryId() == null) {
                todo.setClassifying(false);
                repository.save(todo);
            }
        } catch (Exception ignore) {
            /* best effort */
        }
    }

    private void file(Long todoId, String text) throws Exception {
        // Snapshot the current stories into an indexed list. Empty stories count:
        // a story is a category that exists before anything is filed under it.
        List<StoryRef> stories = new ArrayList<>();
        for (StoryClassifier.StoryView story : classifier.candidates()) {
            stories.add(StoryRef.of(story));
        }

        // Ask Claude which story (if any) fits. No stories, a "none" answer, or a
        // failed/timed-out call all resolve to "no match" — the task is then
        // released to run standalone.
        StoryRef chosen = null;
        if (!stories.isEmpty()) {
            int choice = parseChoice(runClaude(buildPrompt(text, stories)), stories.size());
            if (choice > 0) {
                chosen = stories.get(choice - 1);
            }
        }

        // Finalize under a fresh read. The task was created with the `classifying`
        // hold set (and PENDING) so the worker couldn't run it before we decided;
        // only act if it's still in that exact state (not already filed, released,
        // started, or deleted). Clearing the hold lets the worker pick it up.
        Todo todo = repository.findById(todoId).orElse(null);
        if (todo == null || !todo.isClassifying() || todo.getStoryId() != null) {
            return;
        }
        if (chosen != null) {
            todo.setStoryId(chosen.storyId());
            todo.setStoryName(chosen.name());
            todo.setStoryOrder(classifier.nextOrder(
                    repository.findByStoryIdOrderByStoryOrderAsc(chosen.storyId())));
            // Queued (PENDING) as a story step; the worker runs it in chain order.
            log.info("AI-classified todo #{} into story \"{}\".", todoId, chosen.name());
        } else {
            log.debug("AI classify: todo #{} released to run standalone.", todoId);
        }
        todo.setClassifying(false);
        repository.save(todo);
    }

    /** Build the classification prompt. Package-private for testing. */
    static String buildPrompt(String text, List<StoryRef> stories) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are filing a new task into exactly one of the existing projects ")
                .append("(\"stories\") listed below, or into none of them.\n\n");
        sb.append("NEW TASK:\n").append(text.strip()).append("\n\n");
        sb.append("EXISTING STORIES:\n");
        for (int i = 0; i < stories.size(); i++) {
            StoryRef s = stories.get(i);
            sb.append(i + 1).append(". ").append(s.name());
            if (!s.titles().isEmpty()) {
                sb.append(" — ").append(String.join("; ", s.titles()));
            }
            sb.append('\n');
        }
        sb.append("\nWhich story does the new task clearly belong to (same project, goal, or ")
                .append("subject)? Reply with ONLY that story's number. If none is a clear fit, ")
                .append("reply with ONLY the word: none.\n")
                .append("Answer from the text alone — do not use any tools, and write nothing else.");
        return sb.toString();
    }

    private static final Pattern CHOICE = Pattern.compile("(?i)none|\\d+");

    /**
     * Parse Claude's reply into a 1-based story index, or 0 for "none"/unclear.
     * Scans left to right and honours whichever of a number or "none" appears
     * first, ignoring out-of-range numbers. Package-private for testing.
     */
    static int parseChoice(String output, int storyCount) {
        if (output == null) {
            return 0;
        }
        Matcher m = CHOICE.matcher(output);
        while (m.find()) {
            String tok = m.group();
            if (tok.equalsIgnoreCase("none")) {
                return 0;
            }
            try {
                int n = Integer.parseInt(tok);
                if (n >= 1 && n <= storyCount) {
                    return n;
                }
            } catch (NumberFormatException ignore) {
                // impossible given the pattern, but stay defensive
            }
        }
        return 0;
    }

    private String runClaude(String prompt) throws Exception {
        // Run in a neutral scratch dir so the CLI doesn't load a repo's CLAUDE.md
        // or files — this is a pure text question, not an agentic task.
        File dir = new File(System.getProperty("java.io.tmpdir"), "kanban-classify");
        if (!dir.exists() && !dir.mkdirs()) {
            log.warn("Could not create classify scratch dir {}", dir);
        }
        List<String> cmd = List.of(props.getClaudeCommand(), "--output-format", "text", "-p", prompt);
        ProcessBuilder pb = new ProcessBuilder(cmd)
                .directory(dir)
                .redirectErrorStream(true);
        // Same reason as the neutral scratch dir: keep this throwaway call out of the
        // CLI's auto-memory store.
        pb.environment().put("CLAUDE_CODE_DISABLE_AUTO_MEMORY", "1");
        Process p = pb.start();
        p.getOutputStream().close();

        StringBuilder out = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    out.append(line).append('\n');
                }
            } catch (Exception ignore) {
                // partial output is fine; we validate what we got
            }
        }, "ai-classify-reader");
        reader.start();

        if (!p.waitFor(props.getClassifyTimeoutSeconds(), TimeUnit.SECONDS)) {
            p.destroyForcibly();
            log.warn("AI classify timed out after {}s.", props.getClassifyTimeoutSeconds());
            return null;
        }
        reader.join(5_000);
        if (p.exitValue() != 0) {
            log.warn("AI classify CLI exited {}.", p.exitValue());
            return null;
        }
        return out.toString();
    }

    /** A story reduced to what the prompt needs: an id, a name, and step titles. */
    record StoryRef(String storyId, String name, List<String> titles) {
        static StoryRef of(StoryClassifier.StoryView story) {
            String name = story.name();
            List<String> titles = new ArrayList<>();
            for (Todo t : story.steps()) {
                String title = t.getTitle();
                if (title != null && !title.isBlank() && titles.size() < MAX_TITLES_PER_STORY) {
                    titles.add(title.strip());
                }
            }
            return new StoryRef(story.storyId(), name == null ? "Story" : name, titles);
        }
    }
}
