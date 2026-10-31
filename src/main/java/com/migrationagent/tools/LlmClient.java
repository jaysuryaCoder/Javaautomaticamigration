package com.migrationagent.tools;

import java.io.IOException;

public interface LlmClient {
    String complete(String systemPrompt, String userPrompt) throws IOException;
}
