package com.kanban.workflow;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Naming a duplicate. Split out from the API test because the interesting cases —
 * a name already at the length limit, a name that is nothing but a suffix — are
 * awkward to read as HTTP payloads.
 */
class WorkflowCopyNameTest {

    @Test
    void theFirstCopyIsSuffixedAndLaterOnesAreNumbered() {
        assertThat(WorkflowService.copyName("Research", Set.of())).isEqualTo("Research (copy)");
        assertThat(WorkflowService.copyName("Research", Set.of("Research (copy)")))
                .isEqualTo("Research (copy 2)");
        assertThat(WorkflowService.copyName("Research", Set.of("Research (copy)", "Research (copy 2)")))
                .isEqualTo("Research (copy 3)");
    }

    @Test
    void duplicatingADuplicateDoesNotStackSuffixes() {
        // "Research (copy) (copy)" reads as a mistake; the third copy is "(copy 3)".
        assertThat(WorkflowService.copyName("Research (copy)", Set.of("Research (copy)")))
                .isEqualTo("Research (copy 2)");
        assertThat(WorkflowService.copyName("Research (copy 2)",
                Set.of("Research (copy)", "Research (copy 2)"))).isEqualTo("Research (copy 3)");
    }

    @Test
    void aParentheticalThatIsNotACopySuffixIsKept() {
        assertThat(WorkflowService.copyName("Research (2026)", Set.of()))
                .isEqualTo("Research (2026) (copy)");
        assertThat(WorkflowService.copyName("Research (copy of Bob)", Set.of()))
                .isEqualTo("Research (copy of Bob) (copy)");
    }

    @Test
    void theCopyOfALongNameStillFitsWhatTheApiAccepts() {
        // Otherwise the copy opens in the editor and refuses to save, with the user
        // having done nothing wrong.
        String atTheLimit = "n".repeat(200);
        String copy = WorkflowService.copyName(atTheLimit, Set.of());
        assertThat(copy).hasSizeLessThanOrEqualTo(200).endsWith(" (copy)");
    }

    @Test
    void anEmptyOrSuffixOnlyNameStillGetsSomethingToClickOn() {
        assertThat(WorkflowService.copyName(null, Set.of())).isEqualTo("Workflow (copy)");
        assertThat(WorkflowService.copyName("   ", Set.of())).isEqualTo("Workflow (copy)");
        assertThat(WorkflowService.copyName("(copy)", Set.of())).isEqualTo("Workflow (copy)");
    }
}
