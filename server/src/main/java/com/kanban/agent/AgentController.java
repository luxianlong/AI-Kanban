package com.kanban.agent;

import com.kanban.attachment.Attachment;
import com.kanban.attachment.AttachmentService;
import com.kanban.protocol.ClaimRequest;
import com.kanban.protocol.HeartbeatRequest;
import com.kanban.protocol.HeartbeatResponse;
import com.kanban.protocol.ResultRequest;
import com.kanban.protocol.WorkUnit;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The API a worker talks to. Everything here is behind {@link AgentAuthFilter}.
 *
 * <pre>
 *   # 1. ask for work (204 when there is nothing this worker can run)
 *   curl -s -X POST https://youraikanban.com/api/agent/claim \
 *        -H 'Authorization: Bearer $TOKEN' -H 'Content-Type: application/json' \
 *        -d '{"worker":"laptop-01","capabilities":["general"],"protocolVersion":1}'
 *
 *   # 2. stay alive and stream what the session is saying; the reply carries Stop
 *   curl -s -X POST https://youraikanban.com/api/agent/leases/$LEASE/heartbeat \
 *        -H 'Authorization: Bearer $TOKEN' -H 'Content-Type: application/json' \
 *        -d '{"seq":1,"logLines":["🔧 Read  README.md"]}'
 *
 *   # 3. report the facts; the server decides what they mean
 *   curl -s -X POST https://youraikanban.com/api/agent/leases/$LEASE/result \
 *        -H 'Authorization: Bearer $TOKEN' -H 'Content-Type: application/json' \
 *        -d '{"exitCode":0,"timedOut":false,"abandonedBackgroundWork":false,
 *             "stopped":false,"finalOutput":"done","costUsd":0.12,"durationMs":45000}'
 * </pre>
 */
@RestController
@RequestMapping("/api/agent")
@CrossOrigin(origins = "*")
public class AgentController {

    private final AgentService agent;
    private final AttachmentService attachments;

    public AgentController(AgentService agent, AttachmentService attachments) {
        this.agent = agent;
        this.attachments = attachments;
    }

    /** One unit of work, or 204 when there is nothing this worker's capabilities cover. */
    @PostMapping("/claim")
    public ResponseEntity<WorkUnit> claim(@Valid @RequestBody ClaimRequest request) {
        return agent.claim(request)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/leases/{leaseId}/heartbeat")
    public HeartbeatResponse heartbeat(@PathVariable String leaseId,
                                       @RequestBody HeartbeatRequest request) {
        return agent.heartbeat(leaseId, request);
    }

    @PostMapping("/leases/{leaseId}/result")
    public ResponseEntity<Void> result(@PathVariable String leaseId,
                                       @Valid @RequestBody ResultRequest request) {
        agent.report(leaseId, request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/leases/{leaseId}/release")
    public ResponseEntity<Void> release(@PathVariable String leaseId) {
        agent.release(leaseId);
        return ResponseEntity.noContent().build();
    }

    /**
     * A file the user attached to a task. Served here rather than reused from
     * {@code /api/attachments} because that one is behind the site password: a worker has a
     * token, not a browser session.
     */
    @GetMapping("/attachments/{id}")
    public ResponseEntity<byte[]> attachment(@PathVariable String id) {
        Attachment meta = attachments.require(id);
        byte[] bytes = attachments.read(meta);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + meta.getFileName() + "\"")
                .contentType(MediaType.parseMediaType(
                        meta.getContentType() == null ? MediaType.APPLICATION_OCTET_STREAM_VALUE
                                : meta.getContentType()))
                .body(bytes);
    }
}
