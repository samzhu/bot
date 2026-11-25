package io.github.samzhu.bot.pricing;

import com.fasterxml.jackson.databind.JsonNode;

/** Native usage rules for this endpoint; monetary rounding is shared by the calculator. */
final class AnthropicMessagesPricing {
    static void calculate(JsonNode raw, PricingCalculator.Context c) {
        var input = c.count(raw, "input_tokens");
        var read = c.count(raw, "cache_read_input_tokens");
        var write = c.count(raw, "cache_creation_input_tokens");
        // Cache creation TTL children partition the reported parent; do not charge both levels.
        var shortTtl = c.count(raw, "cache_creation", "ephemeral_5m_input_tokens");
        var longTtl = c.count(raw, "cache_creation", "ephemeral_1h_input_tokens");
        var output = c.count(raw, "output_tokens");
        c.line("input", input); c.line("cache_read", read);
        c.ttl(write, shortTtl, longTtl, "cache_write_5m", "cache_write_1h");
        c.line("output", output);
    }
    private AnthropicMessagesPricing() {}
}
