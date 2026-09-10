package com.kanban.todo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link StorySuggestionService#keywords} / {@link
 * StorySuggestionService#relatednessScore}, focused on the CJK tokenization that
 * lets the deterministic classifier match Chinese tasks (previously it dropped
 * every CJK character and scored Chinese text at 0, forcing every Chinese task
 * onto the fuzzy AI pass — the source of erratic mis-filing).
 *
 * <p>The bar that matters is {@link StoryClassifier#AUTO_CLASSIFY_MIN_SCORE} (2):
 * a genuinely-related Chinese task should clear it, an incidentally-overlapping
 * one should not.
 */
class StorySuggestionServiceTest {

    private final StorySuggestionService svc = new StorySuggestionService();

    private int min() {
        return StoryClassifier.AUTO_CLASSIFY_MIN_SCORE;
    }

    @Test
    void latinBehaviourIsUnchanged() {
        // Same words as before still tokenize; stop-words and <3-char tokens drop.
        assertTrue(svc.keywords("Fix the gallery layout").contains("gallery"));
        assertTrue(svc.keywords("Fix the gallery layout").contains("layout"));
        assertTrue(svc.keywords("Fix the gallery layout").contains("fix"));
        assertTrue(svc.keywords("the and for with").isEmpty(), "pure stop-words yield nothing");
        // Three shared meaningful words (avatar, gallery, redesign) -> score 3.
        assertEquals(3, svc.relatednessScore(
                "redesign the avatar gallery", "avatar gallery redesign step"));
    }

    @Test
    void chineseWasInvisibleNowTokenizes() {
        // A pure-Chinese task used to yield an empty keyword set (score 0 always).
        assertTrue(svc.keywords("大明王朝游戏").size() >= 2,
                "Chinese text now produces bigram tokens");
    }

    @Test
    void relatedChineseTasksClearTheAutoFileBar() {
        // Two tasks about the same 4-char subject share ≥ 2 bigrams (大明/明王/王朝).
        int score = svc.relatednessScore("优化大明王朝游戏", "大明王朝：加结局引导");
        assertTrue(score >= min(),
                "related Chinese tasks should clear the auto-file bar, was " + score);
    }

    @Test
    void unrelatedChineseTasksStayBelowTheBar() {
        // Different subjects, only generic verbs in common -> should not auto-file.
        int score = svc.relatednessScore("优化大明王朝游戏", "优化房地产研究报告");
        assertTrue(score < min(),
                "unrelated Chinese tasks should stay standalone, was " + score);
    }

    @Test
    void genericVerbBigramsAreTreatedAsStopWords() {
        // "优化"/"测试" are generic dev filler and must not, on their own, link two
        // otherwise-unrelated tasks into the same story.
        assertTrue(svc.keywords("优化测试功能").isEmpty()
                        || !svc.keywords("优化测试功能").contains("优化"),
                "generic CJK bigrams are dropped");
        // Only a boundary-spanning bigram can survive (化测), so the score stays
        // below the auto-file bar — two unrelated tasks won't be joined.
        assertTrue(svc.relatednessScore("优化测试股票池", "优化测试房价表") < min(),
                "sharing only generic verbs stays below the auto-file bar");
    }

    @Test
    void mixedLatinAndChineseBothCount() {
        var kw = svc.keywords("重构 gallery 组件");
        assertTrue(kw.contains("gallery"), "keeps Latin word");
        assertTrue(kw.contains("组件"), "keeps topical Chinese bigram");
        assertTrue(!kw.contains("重构"), "drops generic Chinese bigram");
    }
}
