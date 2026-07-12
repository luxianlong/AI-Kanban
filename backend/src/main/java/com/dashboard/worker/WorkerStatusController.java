package com.dashboard.worker;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Exposes worker config so the UI can show whether auto-resolution is on. */
@RestController
@RequestMapping("/api/worker")
@CrossOrigin(origins = "*")
public class WorkerStatusController {

    private final WorkerProperties props;

    public WorkerStatusController(WorkerProperties props) {
        this.props = props;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of(
                "enabled", props.isEnabled(),
                "pollIntervalMs", props.getPollIntervalMs(),
                "batchSize", props.getBatchSize(),
                "timeoutSeconds", props.getTimeoutSeconds());
    }
}
