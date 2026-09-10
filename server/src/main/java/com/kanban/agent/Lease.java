package com.kanban.agent;

import com.kanban.protocol.WorkKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One claim on one unit of work.
 *
 * <p>This is what replaces "the worker is the process that holds the row". When execution
 * lived in this JVM, a session that died died with the server, so a task left IN_PROGRESS at
 * startup was known to be orphaned and could be recovered on the spot. A remote worker can
 * disappear without the server noticing anything at all — the machine sleeps, the wifi drops,
 * someone closes the laptop — so "is this work still being done?" has to become a question
 * with a deadline. A lease answers it: hold it by heartbeating, and if you stop,
 * {@link LeaseReaper} takes the work back on exactly the terms the old restart-recovery used.
 *
 * <p>The id doubles as the credential for reporting against this claim, the same way
 * {@code DelegatedTaskController} issues a token on claim. Knowing a task id is not enough to
 * write a result onto it.
 */
@Entity
@Table(name = "AGENT_LEASES")
public class Lease {

    @Id
    private String id;

    /** The worker's own name for itself. Shown on the board so a card says where it ran. */
    @Column(nullable = false)
    private String workerName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WorkKind kind;

    /** Id of the task (or, later, the node run) this claim is against. */
    @Column(nullable = false)
    private Long refId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LeaseState state = LeaseState.ACTIVE;

    @Column(nullable = false)
    private Instant claimedAt;

    /** After this, the reaper may take the work back. Pushed forward by every heartbeat. */
    @Column(nullable = false)
    private Instant expiresAt;

    /** Last heartbeat seen. A repeat or older sequence is ignored, so a retried heartbeat
     *  cannot append the same lines to the transcript twice. */
    @Column(nullable = false)
    private long lastSeq;

    /** Set when the user presses Stop. The worker reads it on its next heartbeat — the only
     *  way to reach a worker the server cannot dial. */
    @Column(nullable = false)
    private boolean stopRequested;

    /**
     * The clocks this unit was actually handed. Stored because the outcome message has to name
     * the timeout the session really ran under, which is not necessarily this server's current
     * default by the time the result comes back.
     */
    @Column(nullable = false)
    private long timeoutSeconds;

    @Column(nullable = false)
    private long backgroundWaitSeconds;

    protected Lease() {
    }

    public Lease(String id, String workerName, WorkKind kind, Long refId, Instant claimedAt,
                 Instant expiresAt, long timeoutSeconds, long backgroundWaitSeconds) {
        this.id = id;
        this.workerName = workerName;
        this.kind = kind;
        this.refId = refId;
        this.claimedAt = claimedAt;
        this.expiresAt = expiresAt;
        this.timeoutSeconds = timeoutSeconds;
        this.backgroundWaitSeconds = backgroundWaitSeconds;
    }

    public String getId() {
        return id;
    }

    public String getWorkerName() {
        return workerName;
    }

    public WorkKind getKind() {
        return kind;
    }

    public Long getRefId() {
        return refId;
    }

    public LeaseState getState() {
        return state;
    }

    public void setState(LeaseState state) {
        this.state = state;
    }

    public Instant getClaimedAt() {
        return claimedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public long getLastSeq() {
        return lastSeq;
    }

    public void setLastSeq(long lastSeq) {
        this.lastSeq = lastSeq;
    }

    public boolean isStopRequested() {
        return stopRequested;
    }

    public void setStopRequested(boolean stopRequested) {
        this.stopRequested = stopRequested;
    }

    public long getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public long getBackgroundWaitSeconds() {
        return backgroundWaitSeconds;
    }
}
