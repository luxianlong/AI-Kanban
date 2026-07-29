package com.kanban.attachment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface AttachmentRepository extends JpaRepository<Attachment, String> {

    List<Attachment> findByTodoIdOrderByCreatedAtAsc(Long todoId);

    List<Attachment> findByTodoIdInOrderByCreatedAtAsc(List<Long> todoIds);

    /** Uploads that were never submitted with a task (composer cancelled/abandoned). */
    List<Attachment> findByTodoIdIsNullAndCreatedAtBefore(Instant cutoff);
}
