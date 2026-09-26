package com.socialmcp.media;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The real {@link Downloader} against a local HTTP server (scheme and address checks are the loader's).
 */
class DownloaderTest {

    private final AtomicReference<String> authorization = new AtomicReference<>();
    private HttpServer server;
    private String base;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/image", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = new byte[5000];
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.createContext("/moved", exchange -> {
            exchange.getResponseHeaders().add("Location", "/image");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/slow", exchange -> {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void readsAtMostMaxBytesAndSendsNoCredentials() throws Exception {
        Downloader.Response response = Downloader.http().get(URI.create(base + "/image"), Duration.ofSeconds(5), 1001);
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).hasSize(1001);
        assertThat(authorization.get()).isNull();
    }

    @Test
    void redirectsAreReturnedNotFollowed() throws Exception {
        Downloader.Response response = Downloader.http().get(URI.create(base + "/moved"), Duration.ofSeconds(5), 100);
        assertThat(response.status()).isEqualTo(302);
        assertThat(response.location()).isEqualTo("/image");
    }

    @Test
    void theWholeRequestIsTimed() {
        assertThatThrownBy(() -> Downloader.http().get(URI.create(base + "/slow"), Duration.ofMillis(500), 100))
                .isInstanceOf(HttpTimeoutException.class);
    }

}
