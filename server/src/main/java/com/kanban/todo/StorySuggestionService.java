package com.kanban.todo;

import com.kanban.todo.TodoDtos.StoryStep;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Turns a free-text goal into a sensible, ordered chain of sub-tasks, and scores
 * how related a piece of text is to an existing story. This is the "intelligence"
 * behind the story feature: rather than making a human hand-type every step, the
 * service recognises the common shape of a software task (design → implement →
 * test → document, with backend/frontend split when the goal calls for it) and
 * proposes the steps; and rather than making a human remember which story a new
 * task belongs to, it matches the task's words against existing chains.
 *
 * <p>Everything here is deterministic and dependency-free so it stays fast and
 * fully unit-testable — no model call, no network.
 */
@Service
public class StorySuggestionService {

    // Very small stop-word list so relatedness/keyword matching ignores filler.
    private static final Set<String> STOP_WORDS = Set.of(
            "the", "and", "for", "with", "that", "this", "into", "from", "add",
            "make", "should", "would", "could", "task", "story", "when", "then",
            "have", "will", "your", "our", "its", "their", "a", "an", "to", "of",
            "in", "on", "it", "is", "be", "as", "at", "by", "or", "we");

    // Generic CJK bigrams — the Chinese equivalent of the stop-words above. These
    // are the filler verbs/nouns shared across otherwise-unrelated dev tasks
    // (优化/修复/测试/功能…); indexing them would let two tasks that merely both
    // "优化…" or "修复…" collide into the same story. Topical bigrams (游戏, 王朝,
    // 房价…) are what we actually want to match on, so only the generic ones are
    // dropped. See {@link #keywords}.
    private static final Set<String> CJK_STOP_BIGRAMS = Set.of(
            "优化", "修复", "修改", "测试", "更新", "添加", "增加", "实现", "支持",
            "功能", "问题", "任务", "文档", "数据", "显示", "完成", "处理", "系统",
            "页面", "界面", "按钮", "调整", "改进", "重构", "删除", "创建");

    private static final List<String> BACKEND_HINTS = List.of(
            "api", "endpoint", "backend", "server", "database", "db", "model",
            "schema", "persist", "repository", "controller", "service", "query");

    private static final List<String> FRONTEND_HINTS = List.of(
            "ui", "frontend", "page", "button", "screen", "view", "component",
            "css", "style", "styling", "layout", "modal", "drawer", "toggle",
            "dark mode", "form", "badge", "card", "column");

    private static final List<String> DOCS_HINTS = List.of(
            "doc", "docs", "documentation", "readme", "guide");

    private static final List<String> BUGFIX_HINTS = List.of(
            "fix", "bug", "broken", "crash", "error", "regression", "fails",
            "failing", "incorrect", "wrong", "defect");

    /** A proposed story: a display name plus its ordered steps. */
    public record SuggestedStory(String name, List<StoryStep> steps) {
    }

    /**
     * Decompose {@code goal} into an ordered list of sub-tasks. The goal is any
     * short description of what the user wants to achieve; the returned steps are
     * ready to hand straight to {@link StoryController#create}.
     */
    public SuggestedStory suggest(String goal) {
        String clean = goal == null ? "" : goal.trim();
        String lower = clean.toLowerCase();
        String subject = shorten(clean);
        List<StoryStep> steps = new ArrayList<>();

        if (matchesAny(lower, BUGFIX_HINTS)) {
            // A fix reads differently from a feature: understand it before touching code.
            steps.add(step("Reproduce and diagnose the problem",
                    "Confirm the failure for: " + subject + ". Pin down the root cause before changing anything."));
            steps.add(step("Implement the fix", "Apply the smallest change that resolves: " + subject + "."));
            steps.add(step("Add a regression test", "Cover the bug so it cannot silently come back."));
        } else {
            steps.add(step("Design and plan the change",
                    "Sketch the approach for: " + subject + ". Note the files to touch and any edge cases."));

            boolean backend = matchesAny(lower, BACKEND_HINTS);
            boolean frontend = matchesAny(lower, FRONTEND_HINTS);
            if (backend) {
                steps.add(step("Implement the backend",
                        "Build the API/data changes needed for: " + subject + "."));
            }
            if (frontend) {
                steps.add(step("Build the UI",
                        "Wire up the interface for: " + subject + "."));
            }
            if (!backend && !frontend) {
                steps.add(step("Implement the change", "Make the core change for: " + subject + "."));
            }
            steps.add(step("Add tests", "Add or update tests so the new behaviour is covered by the build gate."));
            if (!matchesAny(lower, DOCS_HINTS)) {
                steps.add(step("Update documentation", "Note the change where a future reader would look for it."));
            } else {
                steps.add(step("Write the documentation", "Document: " + subject + "."));
            }
        }
        return new SuggestedStory(deriveName(clean), steps);
    }

    /**
     * Relevance score of {@code text} against a story described by {@code haystack}
     * (typically the story name joined with its step titles): the number of
     * meaningful words they share. Zero means unrelated.
     */
    public int relatednessScore(String text, String haystack) {
        Set<String> a = keywords(text);
        Set<String> b = keywords(haystack);
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        int shared = 0;
        for (String w : a) {
            if (b.contains(w)) {
                shared++;
            }
        }
        return shared;
    }

    /**
     * Meaningful tokens of {@code text}: Latin/digit words of length ≥ 3 (minus
     * stop-words), plus CJK bigrams (minus generic ones).
     *
     * <p>The Latin split on {@code [^a-z0-9]+} drops every CJK character, so a
     * purely-Chinese task used to yield an empty set and always score 0 — leaving
     * every Chinese task to fall through to the fuzzy async AI pass, which is the
     * documented source of erratic mis-filing. To give the deterministic classifier
     * real signal on Chinese, each run of Han characters is indexed as overlapping
     * bigrams (the standard CJK matching trick): a shared 3+-character term then
     * yields ≥ 2 shared bigrams — enough to clear {@link StoryClassifier#AUTO_CLASSIFY_MIN_SCORE}
     * — while a lone generic 2-character verb stays below it.
     */
    public Set<String> keywords(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null) {
            return out;
        }
        for (String raw : text.toLowerCase().split("[^a-z0-9]+")) {
            if (raw.length() >= 3 && !STOP_WORDS.contains(raw)) {
                out.add(raw);
            }
        }
        addCjkBigrams(text, out);
        return out;
    }

    /** Index each run of Han characters in {@code text} as bigrams into {@code out}. */
    private static void addCjkBigrams(String text, Set<String> out) {
        int n = text.length();
        int i = 0;
        while (i < n) {
            if (!isHan(text.charAt(i))) {
                i++;
                continue;
            }
            int start = i;
            while (i < n && isHan(text.charAt(i))) {
                i++;
            }
            String run = text.substring(start, i);
            if (run.length() == 1) {
                // A lone Han character (rare in practice) is all the signal there is.
                out.add(run);
            } else {
                for (int k = 0; k + 1 < run.length(); k++) {
                    String bigram = run.substring(k, k + 2);
                    if (!CJK_STOP_BIGRAMS.contains(bigram)) {
                        out.add(bigram);
                    }
                }
            }
        }
    }

    /** True for CJK Unified Ideographs (incl. Ext A) and Compatibility Ideographs. */
    private static boolean isHan(char c) {
        return (c >= '㐀' && c <= '鿿') || (c >= '豈' && c <= '﫿');
    }

    private static StoryStep step(String title, String description) {
        return new StoryStep(title, description);
    }

    private static boolean matchesAny(String lower, List<String> hints) {
        for (String h : hints) {
            if (h.contains(" ")) {
                if (lower.contains(h)) {
                    return true;
                }
            } else if (containsWord(lower, h)) {
                return true;
            }
        }
        return false;
    }

    /** Whole-word containment so "db" doesn't match "double". */
    private static boolean containsWord(String lower, String word) {
        for (String token : lower.split("[^a-z0-9]+")) {
            if (token.equals(word)) {
                return true;
            }
        }
        return false;
    }

    /** A short, title-cased story name derived from the goal's first clause. */
    private static String deriveName(String goal) {
        if (goal.isBlank()) {
            return "New story";
        }
        String first = goal.split("[.!?\\n]")[0].trim();
        String name = shorten(first);
        if (!name.isEmpty()) {
            name = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        }
        return name.isBlank() ? "New story" : name;
    }

    /** Trim to a reasonable length on a word boundary, dropping a trailing period. */
    private static String shorten(String text) {
        String t = text == null ? "" : text.trim().replaceAll("\\.$", "");
        if (t.length() <= 80) {
            return t;
        }
        String cut = t.substring(0, 80);
        int lastSpace = cut.lastIndexOf(' ');
        return (lastSpace > 40 ? cut.substring(0, lastSpace) : cut).trim() + "…";
    }

    // Kept for potential callers that want the raw word list rather than a set.
    List<String> keywordList(String text) {
        return new ArrayList<>(keywords(text));
    }

    static {
        // Guard against an accidentally-empty hint list during refactors.
        assert !BACKEND_HINTS.isEmpty() && !FRONTEND_HINTS.isEmpty()
                && !Arrays.asList(BUGFIX_HINTS, DOCS_HINTS).isEmpty();
    }
}
