package org.mage.test.mtgeek.llm;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.Map;

public class HttpDecisionClient {
    public static final String DEFAULT_ENDPOINT = "http://127.0.0.1:3000/api/mtg-decision";
    private static final int[] DEFAULT_BACKOFF_MS = {1000, 2000, 4000};
    private static final int TIMEOUT_SEC = 30;

    private final String endpoint;
    private final HttpClient http;
    private final int[] backoffMs;

    public HttpDecisionClient() {
        this(DEFAULT_ENDPOINT, HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build(), DEFAULT_BACKOFF_MS);
    }

    HttpDecisionClient(String endpoint, HttpClient http, int[] backoffMs) {
        this.endpoint = endpoint;
        this.http = http;
        this.backoffMs = backoffMs;
    }

    public DecisionResponse decide(Map<String,Object> request) {
        String body = JsonMini.encode(request);
        RuntimeException last = null;
        for (int attempt = 0; attempt <= backoffMs.length; attempt++) {
            if (attempt > 0) sleep(backoffMs[attempt - 1]);
            try {
                HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(TIMEOUT_SEC))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
                HttpResponse<String> resp = http.send(req, BodyHandlers.ofString());
                if (resp.statusCode() == 503) {
                    // Permanent failure: immediate fallback, no retry
                    throw new DecisionFailedException(
                        "HTTP 503: " + resp.body() + " (is dev server's MINIMAX_API_KEY set?)");
                }
                if (resp.statusCode() != 200) {
                    throw new RuntimeException("HTTP " + resp.statusCode() + ": " + resp.body());
                }
                return DecisionResponse.fromJson(resp.body());
            } catch (DecisionFailedException e) {
                throw e;
            } catch (Exception e) {
                last = (e instanceof RuntimeException) ? (RuntimeException) e : new RuntimeException(e);
            }
        }
        String lastMsg = last != null
            ? last.getClass().getSimpleName() + ": " + last.getMessage()
            : "no-cause";
        throw new DecisionFailedException(
            "LLM decision failed after " + (backoffMs.length + 1) + " attempts; last error: " + lastMsg
                + " (is dev server running on " + endpoint + "?)",
            last);
    }

    private void sleep(int ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
