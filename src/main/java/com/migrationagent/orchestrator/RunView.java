package com.migrationagent.orchestrator;

import java.time.Instant;
import java.util.List;

public record RunView(String id, Run.Status status, Instant startedAt, Instant finishedAt, int total, int succeeded,
                      int failed, int skipped, int running, int pending, List<ServiceView> services) {}
