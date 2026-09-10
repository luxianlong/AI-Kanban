package com.kanban.todo;

import com.kanban.todo.AiStoryClassifier.StoryRef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the pure parsing/prompt-building of the AI classifier. The
 * actual Claude call is not exercised here (no CLI in the test env); these cover
 * the logic that turns a model reply into a story choice, which is where the
 * subtle bugs live.
 */
class AiStoryClassifierTest {

    @Test
    void parsesAPlainNumber() {
        assertEquals(2, AiStoryClassifier.parseChoice("2", 3));
    }

    @Test
    void parsesANumberEmbeddedInText() {
        assertEquals(2, AiStoryClassifier.parseChoice("Story 2", 3));
        assertEquals(1, AiStoryClassifier.parseChoice("The answer is 1.", 3));
    }

    @Test
    void noneMeansStandalone() {
        assertEquals(0, AiStoryClassifier.parseChoice("none", 3));
        assertEquals(0, AiStoryClassifier.parseChoice("None of them fit.", 3));
    }

    @Test
    void honoursWhicheverComesFirst() {
        // "none" first -> standalone, even if a number follows in prose.
        assertEquals(0, AiStoryClassifier.parseChoice("none, though 2 is closest", 3));
        // a valid number first -> that story, even if "none" appears later.
        assertEquals(2, AiStoryClassifier.parseChoice("2 — definitely not none", 3));
    }

    @Test
    void outOfRangeNumbersAreIgnored() {
        assertEquals(0, AiStoryClassifier.parseChoice("5", 3));
        // skips the out-of-range 9, then honours the valid 3
        assertEquals(3, AiStoryClassifier.parseChoice("9? no. 3.", 3));
    }

    @Test
    void nullOrEmptyIsStandalone() {
        assertEquals(0, AiStoryClassifier.parseChoice(null, 3));
        assertEquals(0, AiStoryClassifier.parseChoice("   ", 3));
    }

    @Test
    void promptListsEveryStoryAndTheTask() {
        List<StoryRef> stories = List.of(
                new StoryRef("s1", "科创板投资", List.of("梳理科创板市场机制", "分析重点行业")),
                new StoryRef("s2", "Redesign gallery", List.of("Design", "Build UI")));
        String prompt = AiStoryClassifier.buildPrompt("分析688072 拓荆科技", stories);

        assertTrue(prompt.contains("分析688072 拓荆科技"), "includes the task text");
        assertTrue(prompt.contains("1. 科创板投资"), "numbers the first story");
        assertTrue(prompt.contains("2. Redesign gallery"), "numbers the second story");
        assertTrue(prompt.contains("梳理科创板市场机制"), "includes step titles");
        assertTrue(prompt.toLowerCase().contains("none"), "offers the none option");
    }
}
