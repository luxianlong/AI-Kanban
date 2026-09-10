package com.kanban.pages;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Limits for the public static-page upload API, bound from the {@code pages.*}
 * namespace in {@code application.yml}.
 *
 * <p>The endpoint is deliberately unauthenticated (task #470), so these caps are
 * the only thing standing between the board and someone filling the disk. Keep
 * them conservative.
 */
@ConfigurationProperties(prefix = "pages")
public class PagesProperties {

    /** Largest single page accepted, in bytes. */
    private long maxBytes = 5 * 1024 * 1024;

    /** How many uploaded pages may exist at once; further uploads are refused. */
    private int maxPages = 200;

    /** Uploads allowed from one client address per rolling hour. */
    private int maxUploadsPerHour = 30;

    public long getMaxBytes() {
        return maxBytes;
    }

    public void setMaxBytes(long maxBytes) {
        this.maxBytes = maxBytes;
    }

    public int getMaxPages() {
        return maxPages;
    }

    public void setMaxPages(int maxPages) {
        this.maxPages = maxPages;
    }

    public int getMaxUploadsPerHour() {
        return maxUploadsPerHour;
    }

    public void setMaxUploadsPerHour(int maxUploadsPerHour) {
        this.maxUploadsPerHour = maxUploadsPerHour;
    }
}
