package com.kanban.attachment;

/** Payloads for the attachment API. */
public final class AttachmentDtos {

    private AttachmentDtos() {
    }

    /**
     * How one attachment is shown to clients: enough to render a thumbnail
     * ({@code url}) and a caption, never the on-disk path.
     */
    public record AttachmentView(
            String id,
            String fileName,
            String contentType,
            long sizeBytes,
            String url) {

        public static AttachmentView from(Attachment a) {
            return new AttachmentView(
                    a.getId(),
                    a.getFileName(),
                    a.getContentType(),
                    a.getSizeBytes(),
                    "/api/attachments/" + a.getId());
        }
    }
}
