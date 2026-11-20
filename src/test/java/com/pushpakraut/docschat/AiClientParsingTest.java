package com.pushpakraut.docschat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Real response shapes: Gemini leaves out "index", Ollama/OpenAI include it (possibly out of order). */
class AiClientParsingTest {
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void geminiStyleWithoutIndexKeepsOrder() {
        var r = json.readValue("{\"data\":[{\"embedding\":[1.0]},{\"embedding\":[2.0]}]}", AiClient.EmbedResponse.class);
        var v = AiClient.toVectors(r);
        assertEquals(1.0f, v.get(0)[0]);
        assertEquals(2.0f, v.get(1)[0]);
    }

    @Test
    void openAiStyleIsSortedByIndex() {
        var r = json.readValue("{\"data\":[{\"index\":1,\"embedding\":[2.0]},{\"index\":0,\"embedding\":[1.0]}]}", AiClient.EmbedResponse.class);
        var v = AiClient.toVectors(r);
        assertEquals(1.0f, v.get(0)[0]);
        assertEquals(2.0f, v.get(1)[0]);
    }
}
