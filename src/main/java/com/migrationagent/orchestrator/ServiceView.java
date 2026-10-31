package com.migrationagent.orchestrator;

import com.migrationagent.model.ServiceStatus;

import java.util.List;

public record ServiceView(String name, ServiceStatus status, String node, int repairAttempts, int llmCalls,
                          List<String> changedFiles, String prUrl, String reason, long durationMs,
                          List<String> dependsOn) {}
