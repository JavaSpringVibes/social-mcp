package com.socialmcp.model;

import org.jspecify.annotations.Nullable;

/**
 * One item of the {@code images} parameter (SPEC §4, Tool 8): an absolute file path, {@code file:} URI or
 * {@code https://} URL, and the alt text. Both are nullable because MCP input is untrusted; SPEC §6.14 checks them.
 */
public record ImageInput(@Nullable String source, @Nullable String altText) {
}
