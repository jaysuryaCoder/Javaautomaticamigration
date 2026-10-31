package com.migrationagent.tools;

import java.io.IOException;
import java.util.concurrent.Semaphore;

/** Caps concurrent LLM calls so 30 parallel agents don't trip provider rate limits. */
public final class ThrottledLlmClient implements LlmClient {

    private final LlmClient delegate;
    private final Semaphore permits;

    public ThrottledLlmClient(LlmClient delegate, int maxConcurrent) {
        this.delegate = delegate;
        this.permits = new Semaphore(Math.max(1, maxConcurrent));
    }

    @Override
    public String complete(String systemPrompt, String userPrompt) throws IOException {
        try {
            permits.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for an LLM slot", e);
        }
        try {
            return delegate.complete(systemPrompt, userPrompt);
        } finally {
            permits.release();
        }
    }
}
