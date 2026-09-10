package com.kanban;

import com.kanban.agent.AgentService;
import com.kanban.protocol.ClaimRequest;
import com.kanban.todo.Todo;
import com.kanban.todo.TodoRepository;
import com.kanban.todo.TodoStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code worker.enabled=false} must still mean "run nothing".
 *
 * <p>It used to stop the in-process poller, which was the only thing that ran work. With
 * execution outside the server that switch had nowhere left to act, and a worker on another
 * machine would happily keep draining a board its owner had deliberately paused — the switch
 * would look like it worked, because this box's own worker is stopped by the same setting in
 * scripts/env.sh, right up until someone started a second worker elsewhere.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "worker.enabled=false",
        "agent.token=test-worker-token",
        "spring.datasource.url=jdbc:h2:mem:agentpauseddb;DB_CLOSE_DELAY=-1"
})
class AgentPausedBoardTest {

    @Autowired
    AgentService agent;

    @Autowired
    TodoRepository todos;

    @Test
    void aPausedBoardHandsOutNothingEvenToAWorkerThatCanRunIt() {
        todos.deleteAll();
        Todo waiting = new Todo();
        waiting.setTitle("Should stay put");
        todos.save(waiting);

        Optional<?> claimed =
                agent.claim(new ClaimRequest("laptop-01", List.of("general", "workflow"), 1));

        assertThat(claimed).isEmpty();
        assertThat(todos.findById(waiting.getId()).orElseThrow().getStatus())
                .isEqualTo(TodoStatus.PENDING);
    }
}
