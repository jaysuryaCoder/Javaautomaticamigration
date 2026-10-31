package com.migrationagent.orchestrator;

import java.time.Instant;

/** One real-time event, streamed to the dashboard over SSE. {@code service} and {@code node} may be null. */
public record RunEvent(Instant time, String runId, String service, EventType type, String node, String message) {}
