package com.socialmcp.platform;

import java.util.function.Supplier;

import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Translates upstream failures that no service mapped to a specific message (SPEC §6.5). Messages never include
 * credentials: only the HTTP status and a short excerpt of the response body.
 */
public final class ApiErrors {

	private static final int EXCERPT_LENGTH = 200;

	private ApiErrors() {
	}

	public static <T> T translate(String platform, Supplier<T> call) {
		try {
			return call.get();
		}
		catch (RestClientResponseException ex) {
			throw new IllegalStateException(
					platform + " API error " + ex.getStatusCode().value() + ": " + excerpt(ex.getResponseBodyAsString()),
					ex);
		}
		catch (ResourceAccessException ex) {
			Throwable cause = ex.getMostSpecificCause();
			throw new IllegalStateException(platform + " is unreachable: " + cause.getMessage(), ex);
		}
	}

	private static String excerpt(String body) {
		if (body == null || body.isBlank()) {
			return "(empty response)";
		}
		String flat = body.replaceAll("\\s+", " ").trim();
		return flat.length() <= EXCERPT_LENGTH ? flat : flat.substring(0, EXCERPT_LENGTH) + "…";
	}

}
