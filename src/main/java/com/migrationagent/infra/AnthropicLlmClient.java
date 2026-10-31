package com.migrationagent.infra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.migrationagent.model.MigrationSettings;
import com.migrationagent.tools.LlmClient;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Minimal Anthropic Messages API client (no SDK dependency) with retry on rate limits and overload. */
public final class AnthropicLlmClient implements LlmClient {

    private static final int MAX_ATTEMPTS = 4;

    private final MigrationSettings.Llm cfg;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    public AnthropicLlmClient(MigrationSettings.Llm cfg) {
        this.cfg = cfg;
    }

    @Override
    public String complete(String systemPrompt, String userPrompt) throws IOException {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", cfg.getModel());
        body.put("max_tokens", cfg.getMaxTokens());
        body.put("system", systemPrompt);
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "user").put("content", userPrompt);
        String payload = mapper.writeValueAsString(body);

        HttpRequest request = HttpRequest.newBuilder(URI.create(cfg.getBaseUrl() + "/v1/messages"))
                .timeout(Duration.ofSeconds(cfg.getTimeoutSeconds()))
                .header("content-type", "application/json")
                .header("x-api-key", cfg.getApiKey())
                .header("anthropic-version", "2023-06-01")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        IOException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
                int status = resp.statusCode();
                if (status == 200) {
                    return extractText(resp.body());
                }
                String detail = "Anthropic API HTTP " + status + ": " + abbreviate(resp.body());
                if (status == 429 || status == 529 || status >= 500) {
                    last = new IOException(detail);
                    sleep(backoffMillis(attempt, resp.headers().firstValue("retry-after").orElse(null)));
                } else {
                    throw new IOException(detail); // 4xx other than 429: retrying will not help
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while calling the LLM", e);
            } catch (java.net.http.HttpTimeoutException | java.net.ConnectException e) {
                last = e;
                sleep(backoffMillis(attempt, null));
            }
        }
        throw last != null ? last : new IOException("LLM call failed");
    }

    private String extractText(String json) throws IOException {
        JsonNode content = mapper.readTree(json).path("content");
        StringBuilder text = new StringBuilder();
        for (JsonNode part : content) {
            if ("text".equals(part.path("type").asText())) {
                text.append(part.path("text").asText());
            }
        }
        return text.toString();
    }

    private static long backoffMillis(int attempt, String retryAfterSeconds) {
        if (retryAfterSeconds != null) {
            try {
                return Math.min(30_000, Long.parseLong(retryAfterSeconds.trim()) * 1000);
            } catch (NumberFormatException ignored) {
                // fall through to exponential backoff
            }
        }
        return Math.min(20_000, 1_000L << (attempt - 1));
    }

    private static void sleep(long millis) throws IOException {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while backing off", e);
        }
    }

    private static String abbreviate(String s) {
        return s == null ? "" : s.length() <= 300 ? s : s.substring(0, 300) + "...";
    }
}
