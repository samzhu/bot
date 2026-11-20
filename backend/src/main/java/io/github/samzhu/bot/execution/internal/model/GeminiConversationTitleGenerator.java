package io.github.samzhu.bot.execution.internal.model;

import com.google.genai.Client;
import com.google.genai.gaos.models.interactions.CreateModelInteraction;
import com.google.genai.gaos.models.interactions.GenerationConfig;
import com.google.genai.gaos.models.interactions.InteractionStatus;
import com.google.genai.gaos.models.interactions.InteractionsInput;
import com.google.genai.gaos.models.interactions.ModelOutputStep;
import com.google.genai.gaos.models.interactions.TextContent;
import com.google.genai.gaos.models.interactions.ThinkingLevel;
import com.google.genai.gaos.models.operations.CreateInteractionRequestBody;
import com.google.genai.gaos.utils.RetryConfig;
import io.github.samzhu.bot.conversation.ConversationTitleGenerator;

/** One stateless Gemini Interactions request for a title. */
public final class GeminiConversationTitleGenerator implements ConversationTitleGenerator {
    private static final String MODEL = "gemini-3.5-flash-lite";
    private static final String INSTRUCTION = "Write a short, single-line plain-text conversation title in the user's language. Return only the title.";
    private final Client client;
    public GeminiConversationTitleGenerator(Client client) { this.client = client; }
    @Override public String generate(String input) throws Exception { return generateResult(input).title(); }
    @Override public Result generateResult(String input) {
        if (input == null || input.isBlank() || input.length() > 10000) throw new IllegalArgumentException("Invalid title input");
        var request = CreateModelInteraction.builder().model(MODEL).input(InteractionsInput.of(input))
                .systemInstruction(INSTRUCTION).store(false).stream(false).background(false)
                .generationConfig(GenerationConfig.builder().maxOutputTokens(64).thinkingLevel(ThinkingLevel.MINIMAL).build())
                .build();
        var interaction = client.interactions.create().body(CreateInteractionRequestBody.of(request))
                .retryConfig(RetryConfig.noRetries()).call().interaction().orElseThrow();
        var nativeUsage = interaction.usage().orElse(null);
        Usage usage = nativeUsage == null ? null : new Usage(nativeUsage.totalInputTokens().orElse(null),
                nativeUsage.totalCachedTokens().orElse(null), nativeUsage.totalOutputTokens().orElse(null),
                nativeUsage.totalThoughtTokens().orElse(null), nativeUsage.totalToolUseTokens().orElse(null),
                nativeUsage.totalTokens().orElse(null));
        String title = null;
        if (interaction.status().filter(InteractionStatus.COMPLETED::equals).isPresent()) {
            title = interaction.outputText().orElse(null);
            if (title == null && interaction.steps().isPresent()) {
                var text = new StringBuilder();
                for (var step : interaction.steps().orElseThrow()) {
                    if (step instanceof ModelOutputStep output && output.error().isEmpty()) {
                        for (var content : output.content().orElse(java.util.List.of())) {
                            if (content instanceof TextContent part) part.text().ifPresent(text::append);
                        }
                    }
                }
                title = text.toString();
            }
        }
        String responseId = interaction.id().filter(id -> id.length() <= 255).orElse(null);
        return new Result(title, responseId, usage);
    }
}
