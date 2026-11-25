package io.github.samzhu.bot.pricing;

import com.fasterxml.jackson.databind.JsonNode;

/** Native usage rules for this endpoint; monetary rounding is shared by the calculator. */
final class OpenAiResponsesPricing {
    static void calculate(JsonNode raw, PricingCalculator.Context c, boolean fixtureTtl) {
        var input = c.count(raw, "input_tokens");
        var read = c.count(raw, "input_tokens_details", "cached_tokens");
        var write = c.count(raw, "input_tokens_details", "cache_write_tokens");
        var shortTtl = fixtureTtl ? c.count(raw, "input_tokens_details", "cache_write", "30m") : null;
        var longTtl = fixtureTtl ? c.count(raw, "input_tokens_details", "cache_write", "12h") : null;
        var output = c.count(raw, "output_tokens");
        // Reasoning is an output subcount; billing it again would double-charge its parent.
        var reasoning = c.optionalCount(raw, "output_tokens_details", "reasoning_tokens");
        if (input != null && read != null && write != null) {
            var ordinary = input.subtract(read).subtract(write);
            if (ordinary.signum() < 0) c.reasons.add(PricingCalculator.Reason.INVALID_USAGE);
            else {
                c.line("input", ordinary);
                c.line("cache_read", read);
                if (fixtureTtl) c.ttl(write, shortTtl, longTtl, "cache_write_30m", "cache_write_12h");
                else c.line("cache_write", write);
            }
        }
        if (output != null && reasoning != null && reasoning.compareTo(output) > 0) c.reasons.add(PricingCalculator.Reason.INVALID_USAGE);
        else c.line("output", output);
    }
    private OpenAiResponsesPricing() {}
}
