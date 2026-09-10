package com.kanban.protocol;

/**
 * One file a user attached to a task, as a thing the worker can fetch.
 *
 * <p>A URL rather than a path: the files live beside the server's database, so on a remote
 * worker the absolute path the server knows means nothing. The worker downloads each one into
 * the unit's working directory and hands the session the local path it ended up at.
 *
 * @param downloadUrl absolute URL on the server; needs the same bearer token as every other
 *                    agent call
 */
public record AttachmentRef(String id, String fileName, String downloadUrl) {
}
