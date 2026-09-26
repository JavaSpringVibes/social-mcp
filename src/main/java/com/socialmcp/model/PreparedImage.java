package com.socialmcp.model;

/**
 * Internal: an image that passed SPEC §6.14, ready to upload. {@code bytes} already has metadata stripped where the
 * platform needs it; {@code width} and {@code height} are after EXIF orientation. {@code index} is 1-based.
 */
public record PreparedImage(int index, byte[] bytes, String mimeType, int width, int height, String altText) {

    /**
     * The file extension for the MIME type, e.g. {@code jpg}.
     */
    public String extension() {
        return switch (mimeType) {
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "image/gif" -> "gif";
            case "image/webp" -> "webp";
            default -> "bin";
        };
    }

}
