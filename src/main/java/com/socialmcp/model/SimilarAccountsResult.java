package com.socialmcp.model;

import java.util.List;

/**
 * Similar accounts plus how they were found (SPEC §4, Tool 7).
 */
public record SimilarAccountsResult(String platform, String method, List<String> basedOn,
                                    List<AccountSummary> accounts) {
}
