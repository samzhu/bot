package io.github.samzhu.bot.pricing;

import com.fasterxml.jackson.databind.JsonNode;

/** Native usage rules for this endpoint; monetary rounding is shared by the calculator. */
final class GeminiInteractionsPricing {
    static void calculate(JsonNode raw, PricingCalculator.Context c) {
        var input=c.count(raw,"total_input_tokens");
        var cached=c.count(raw,"total_cached_tokens");
        var output=c.count(raw,"total_output_tokens");
        var thought=c.count(raw,"total_thought_tokens");
        var tool=c.count(raw,"total_tool_use_tokens");
        var total=c.count(raw,"total_tokens");
        if (input!=null && cached!=null) {
            var ordinary=input.subtract(cached);
            if (ordinary.signum()<0) c.reasons.add(PricingCalculator.Reason.INVALID_USAGE);
            else { c.line("input",ordinary); c.line("cache_read",cached); }
        }
        c.line("output",output); c.line("thoughts",thought);
        if (tool!=null && tool.signum()!=0) c.reasons.add(PricingCalculator.Reason.EXTRA_COST_UNKNOWN);
        if (input!=null && output!=null && thought!=null && total!=null && !input.add(output).add(thought).equals(total))
            c.reasons.add(PricingCalculator.Reason.INVALID_USAGE);
    }
    private GeminiInteractionsPricing() {}
}
