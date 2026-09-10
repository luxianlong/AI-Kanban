package com.kanban.protocol;

/**
 * The marker the server leaves in a prompt where the attached files' paths belong.
 *
 * <p>The server stores those files beside its own database and knows them by absolute paths on
 * its own disk. Those paths are meaningless on a worker's machine — and a session told to open
 * one would simply fail to find it — so the prompt carries this token instead, and the worker
 * replaces it with wherever the files actually landed after it downloaded them.
 */
public final class AttachmentsPlaceholder {

    public static final String TOKEN = "{{ATTACHMENT_PATHS}}";

    private AttachmentsPlaceholder() {
    }
}
