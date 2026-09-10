package com.kanban.worker;

import com.kanban.agent.AgentProperties;
import com.kanban.agent.Lease;
import com.kanban.agent.LeaseRepository;
import com.kanban.agent.LeaseState;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Whether the board is in a state where anything can actually happen.
 *
 * <p>It used to report the in-process poller's settings — its interval, its batch size —
 * because that poller was the thing that ran work. Now that work is run by separate processes,
 * those numbers describe nothing, and the question worth answering is different: is the switch
 * on, is the agent API usable, and <em>is any worker actually connected?</em> A board with no
 * worker looks exactly like a board with nothing to do, and that is the confusion this exists
 * to prevent.
 */
@RestController
@RequestMapping("/api/worker")
@CrossOrigin(origins = "*")
public class WorkerStatusController {

    private final WorkerProperties props;
    private final AgentProperties agentProps;
    private final LeaseRepository leases;

    public WorkerStatusController(WorkerProperties props, AgentProperties agentProps,
                                  LeaseRepository leases) {
        this.props = props;
        this.agentProps = agentProps;
        this.leases = leases;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        List<Lease> active = leases.findByState(LeaseState.ACTIVE);
        // A set, because a worker running two units would otherwise be counted twice; sorted so
        // the list is stable between polls rather than reshuffling in whatever order the rows
        // came back.
        var busy = new TreeSet<String>();
        for (Lease lease : active) {
            busy.add(lease.getWorkerName());
        }

        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", props.isEnabled());
        // False means no agent.token is configured, so the API answers 503 and no worker can
        // connect at all — the likeliest reason for a board where nothing ever starts.
        status.put("agentApiEnabled", agentProps.isEnabled());
        status.put("timeoutSeconds", props.getTimeoutSeconds());
        status.put("leaseSeconds", agentProps.getLeaseSeconds());
        status.put("unitsInFlight", active.size());
        status.put("busyWorkers", List.copyOf(busy));
        return status;
    }
}
