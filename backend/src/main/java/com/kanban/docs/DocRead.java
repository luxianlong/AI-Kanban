package com.kanban.docs;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A record that the user has opened (seen) a given document. Keyed by the doc's
 * manifest id, so it survives regenerations of the static docs site. Persisting
 * "seen" server-side — rather than in each browser's localStorage — is what lets
 * the "new docs" badge stay in sync across every device the same user browses
 * from: open a report on your laptop and its badge clears on your phone too.
 */
@Entity
@Table(name = "doc_reads")
public class DocRead {

    /** The document's manifest id (matches an entry in docs-manifest.json). */
    @Id
    private String docId;

    /** When the doc was first marked seen. */
    @Column(nullable = false)
    private Instant seenAt;

    protected DocRead() {
    }

    public DocRead(String docId, Instant seenAt) {
        this.docId = docId;
        this.seenAt = seenAt;
    }

    public String getDocId() {
        return docId;
    }

    public Instant getSeenAt() {
        return seenAt;
    }
}
