package com.migrationagent.tools;

/** Everything an agent can touch. Swap the whole box to simulate, or to test against fakes. */
public record Toolbox(GitOps git, RewriteRunner rewrite, BuildRunner build, LlmClient llm) {}
