package com.socialmcp.model;

import java.util.List;

/**
 * The URLs of a published thread, first post first (SPEC §4, Tool 11).
 */
public record ThreadResult(String platform, int partsPosted, List<String> urls) {
}
