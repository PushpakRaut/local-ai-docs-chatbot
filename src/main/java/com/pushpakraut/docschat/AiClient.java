package com.pushpakraut.docschat;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Talks to any OpenAI-compatible API. The same code works with:
 *  - Ollama on your machine (default, free, private): http://localhost:11434/v1
 *  - Google Gemini free tier (for hosting):           https://generativelanguage.googleapis.com/v1beta/openai
 *  - OpenAI or any other compatible provider.
 */
@Component
public class AiClient {
    private final RestClient http;
    private final String chatModel;
    private final String embedModel;
    private final Integer embedDimensions;

    record EmbedResponse(List<EmbedItem> data) {}
    record EmbedItem(Integer index, float[] embedding) {} // Gemini omits "index"; Ollama/OpenAI include it
    record ChatResponse(List<Choice> choices) {}
    record Choice(Message message) {}
    record Message(String role, String content) {}

    public AiClient(@Value("${app.ai.base-url}") String baseUrl,
                    @Value("${app.ai.api-key}") String apiKey,
                    @Value("${app.ai.chat-model}") String chatModel,
                    @Value("${app.ai.embed-model}") String embedModel,
                    @Value("${app.ai.embed-dimensions}") String embedDimensions) {
        RestClient.Builder b = RestClient.builder().baseUrl(baseUrl.replaceAll("/+$", ""));
        if (!apiKey.isBlank()) b.defaultHeader("Authorization", "Bearer " + apiKey);
        this.http = b.build();
        this.chatModel = chatModel;
        this.embedModel = embedModel;
        this.embedDimensions = embedDimensions.isBlank() ? null : Integer.valueOf(embedDimensions.trim());
    }

    /** Turns each text into a vector that captures its meaning. */
    public List<float[]> embed(List<String> texts) {
        EmbedResponse r = http.post().uri("/embeddings")
                .body(embedDimensions == null
                        ? Map.of("model", embedModel, "input", texts)
                        : Map.of("model", embedModel, "input", texts, "dimensions", embedDimensions))
                .retrieve().body(EmbedResponse.class);
        if (r == null || r.data() == null || r.data().size() != texts.size()) {
            throw new IllegalStateException("The AI service returned no embeddings. Is it running, and is the model available?");
        }
        return toVectors(r);
    }

    /** Orders by "index" when the provider sends it, otherwise keeps the order returned. */
    static List<float[]> toVectors(EmbedResponse r) {
        var items = r.data().stream();
        if (r.data().stream().allMatch(i -> i.index() != null)) items = items.sorted(Comparator.comparingInt(EmbedItem::index));
        return items.map(EmbedItem::embedding).toList();
    }

    public String chat(String system, String user) {
        ChatResponse r = http.post().uri("/chat/completions")
                .body(Map.of("model", chatModel, "temperature", 0.1,
                        "messages", List.of(new Message("system", system), new Message("user", user))))
                .retrieve().body(ChatResponse.class);
        if (r == null || r.choices() == null || r.choices().isEmpty()) throw new IllegalStateException("The AI service returned no answer");
        return r.choices().getFirst().message().content();
    }
}
