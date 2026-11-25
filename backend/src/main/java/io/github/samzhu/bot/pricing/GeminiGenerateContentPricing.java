package io.github.samzhu.bot.pricing;

import com.fasterxml.jackson.databind.JsonNode;

/** Native usage rules for this endpoint; monetary rounding is shared by the calculator. */
final class GeminiGenerateContentPricing {
    static void calculate(JsonNode raw, PricingCalculator.Context c) {
        var prompt = c.count(raw, "promptTokenCount");
        var cached = c.count(raw, "cachedContentTokenCount");
        var output = c.count(raw, "candidatesTokenCount");
        var thoughts = c.count(raw, "thoughtsTokenCount");
        var tool = c.count(raw, "toolUsePromptTokenCount");
        // Gemini cached content is already included in prompt; preserve raw and price the disjoint parts.
        if (prompt != null && cached != null) {
            var ordinary = prompt.subtract(cached);
            if (ordinary.signum() < 0) c.reasons.add(PricingCalculator.Reason.INVALID_USAGE);
            else { c.line("input", ordinary); c.line("cache_read", cached); }
        }
        c.line("output", output); c.line("thoughts", thoughts); c.line("tool_input", tool);
    }
    private GeminiGenerateContentPricing() {}
}
