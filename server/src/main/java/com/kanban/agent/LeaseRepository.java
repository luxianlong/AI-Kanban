package com.kanban.agent;

import com.kanban.protocol.WorkKind;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface LeaseRepository extends JpaRepository<Lease, String> {

    List<Lease> findByStateAndExpiresAtBefore(LeaseState state, Instant cutoff);

    List<Lease> findByState(LeaseState state);

    Optional<Lease> findByKindAndRefIdAndState(WorkKind kind, Long refId, LeaseState state);
}
