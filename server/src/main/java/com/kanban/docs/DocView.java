package com.kanban.docs;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * How many times a document has been opened (task #523). Keyed by the doc's
 * manifest id — the same key as {@link DocRead} — so the tally survives every
 * regeneration of the static docs site.
 *
 * <p>Counting happens server-side: the Vite gate increments the tally when it
 * serves a doc page, so a visit is recorded even for pages that carry no script
 * of their own (uploaded pages under {@code /p/}, bespoke pages at the web root)
 * and even when the reader blocks JavaScript.
 */
@Entity
@Table(name = "doc_views")
public class DocView {

    /** The document's manifest id (matches an entry in docs-manifest.json). */
    @Id
    private String docId;

    /** Total number of recorded visits. */
    @Column(name = "view_count", nullable = false)
    private long viewCount;

    /** When the most recent visit was recorded. */
    @Column(nullable = false)
    private Instant lastViewedAt;

    protected DocView() {
    }

    public DocView(String docId, long viewCount, Instant lastViewedAt) {
        this.docId = docId;
        this.viewCount = viewCount;
        this.lastViewedAt = lastViewedAt;
    }

    public String getDocId() {
        return docId;
    }

    public long getViewCount() {
        return viewCount;
    }

    public Instant getLastViewedAt() {
        return lastViewedAt;
    }
}
