package com.socialmcp.platform;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpServerErrorException;

/** Null-safe accessors over Jackson 3 trees returned by the platform APIs. */
public final class Json {

	private Json() {
	}

	/**
	 * Returns a response body, treating a missing body as an upstream error. {@code RestClient} returns null for an
	 * empty body, and none of the endpoints used here legitimately returns one.
	 */
	public static JsonNode required(@Nullable JsonNode body, String platform) {
		if (body == null) {
			throw new HttpServerErrorException(HttpStatus.BAD_GATEWAY, "Empty response from " + platform);
		}
		return body;
	}

	/** The field's text, or {@code ""} when the node or field is missing or null. */
	public static String text(@Nullable JsonNode node, String field) {
		JsonNode value = node == null ? null : node.get(field);
		if (value == null || value.isNull() || value.isMissingNode()) {
			return "";
		}
		return value.isString() ? value.stringValue() : value.asString("");
	}

	public static long number(@Nullable JsonNode node, String field) {
		JsonNode value = node == null ? null : node.get(field);
		if (value == null || value.isNull()) {
			return 0;
		}
		return value.asLong(0);
	}

	public static boolean isPresent(@Nullable JsonNode node) {
		return node != null && !node.isNull() && !node.isMissingNode();
	}

	public static List<JsonNode> array(@Nullable JsonNode node, String field) {
		return elements(node == null ? null : node.get(field));
	}

	public static List<JsonNode> elements(@Nullable JsonNode array) {
		List<JsonNode> result = new ArrayList<>();
		if (array != null && array.isArray()) {
			array.values().forEach(result::add);
		}
		return result;
	}

	/** Normalizes a platform timestamp to ISO-8601 UTC, or returns null when absent. */
	public static @Nullable String isoUtc(@Nullable String timestamp) {
		if (timestamp == null || timestamp.isBlank()) {
			return null;
		}
		try {
			return OffsetDateTime.parse(timestamp).toInstant().toString();
		}
		catch (DateTimeParseException ex) {
			return timestamp;
		}
	}

	/** Parses an ISO timestamp for ordering; absent or unparseable values sort as the epoch. */
	public static Instant instant(@Nullable String isoTimestamp) {
		if (isoTimestamp == null) {
			return Instant.EPOCH;
		}
		try {
			return Instant.parse(isoTimestamp);
		}
		catch (DateTimeParseException ex) {
			return Instant.EPOCH;
		}
	}

}
