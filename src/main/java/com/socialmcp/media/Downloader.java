package com.socialmcp.media;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.jspecify.annotations.Nullable;

/**
 * One HTTP GET for an image download (SPEC §6.14, step 6), without following redirects: the caller follows them so it
 * can check each target. Never sends credentials.
 */
@FunctionalInterface
public interface Downloader {

	/** The status, the {@code Location} header (for redirects) and at most {@code maxBytes} bytes of the body. */
	record Response(int status, @Nullable String location, byte[] body) {
	}

	/** @param timeout the limit for the whole request, including reading the body */
	Response get(URI uri, Duration timeout, long maxBytes) throws IOException, InterruptedException;

	/** The real implementation, on {@link HttpClient} with a 10 s connect timeout. */
	static Downloader http() {
		HttpClient client = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.followRedirects(HttpClient.Redirect.NEVER)
			.build();
		return (uri, timeout, maxBytes) -> {
			AtomicReference<@Nullable InputStream> open = new AtomicReference<>();
			CompletableFuture<Response> download = CompletableFuture.supplyAsync(() -> {
				try {
					HttpRequest request = HttpRequest.newBuilder(uri).timeout(timeout).GET().build();
					HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
					try (InputStream body = response.body()) {
						open.set(body);
						byte[] bytes = response.statusCode() / 100 == 2
								? body.readNBytes((int) Math.min(maxBytes, Integer.MAX_VALUE - 8)) : new byte[0];
						return new Response(response.statusCode(), response.headers().firstValue("Location").orElse(null),
								bytes);
					}
				}
				catch (IOException ex) {
					throw new UncheckedIOException(ex);
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
					throw new UncheckedIOException(new IOException("interrupted"));
				}
			});
			try {
				return download.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
			}
			catch (TimeoutException ex) {
				download.cancel(true);
				InputStream body = open.get();
				if (body != null) {
					body.close();
				}
				throw new HttpTimeoutException("timed out after " + timeout.toSeconds() + " s");
			}
			catch (ExecutionException ex) {
				Throwable cause = ex.getCause();
				if (cause instanceof UncheckedIOException io) {
					throw io.getCause();
				}
				throw new IOException(cause == null ? "download failed" : cause.getMessage(), cause);
			}
		};
	}

}
