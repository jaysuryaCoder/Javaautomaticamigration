package com.migrationagent.model;

import java.util.List;

public record ProjectInfo(String buildTool, String javaVersion, String springBootVersion, boolean lombok,
                          List<String> warnings) {}
