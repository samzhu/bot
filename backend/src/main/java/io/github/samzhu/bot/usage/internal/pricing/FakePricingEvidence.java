package io.github.samzhu.bot.usage.internal.pricing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.samzhu.bot.usage.UsageLedger;
import java.util.Set;

/** Narrow, server-only synthetic contract; no claim about a real provider wire schema. */
final class FakePricingEvidence {
    record Dialect(String provider, String endpoint, boolean textOnlyEvidence) {}
    private static final ObjectMapper JSON = new ObjectMapper();
    static Dialect dialect(UsageLedger.PricingInputView input) {
        var s=input.effectiveSnapshot();
        if (!input.simulation() || !"FAKE".equals(input.channel()) || s==null || !"FAKE_RAW".equals(s.sourceLayer())
                || !"pricing-fixture-v1".equals(s.schemaVersion())) return null;
        String provider; String endpoint;
        switch (input.provider()+":"+input.endpoint()) {
            case "FAKE_GEMINI:generateContent" -> { provider="gemini"; endpoint="generateContent"; }
            case "FAKE_OPENAI:responses-fixture-ttl" -> { provider="openai"; endpoint="responses-fixture-ttl"; }
            case "FAKE_ANTHROPIC:messages" -> { provider="anthropic"; endpoint="messages"; }
            default -> { return null; }
        }
        return new Dialect(provider,endpoint,"COMPLETE".equals(s.captureCompleteness()) && allowed(provider,s.rawJson()));
    }
    private static boolean allowed(String provider, String raw) {
        try {
            JsonNode n=JSON.readTree(raw);
            if (!n.isObject()) return false;
            return switch (provider) {
                case "gemini" -> only(n,Set.of("promptTokenCount","cachedContentTokenCount","candidatesTokenCount","thoughtsTokenCount","toolUsePromptTokenCount","totalTokenCount"))
                    && n.path("toolUsePromptTokenCount").isIntegralNumber() && n.path("toolUsePromptTokenCount").bigIntegerValue().signum()==0;
                case "openai" -> only(n,Set.of("input_tokens","input_tokens_details","output_tokens","output_tokens_details"))
                    && only(n.path("input_tokens_details"),Set.of("cached_tokens","cache_write_tokens","cache_write"))
                    && only(n.path("input_tokens_details").path("cache_write"),Set.of("30m","12h"))
                    && only(n.path("output_tokens_details"),Set.of("reasoning_tokens"));
                case "anthropic" -> only(n,Set.of("input_tokens","cache_read_input_tokens","cache_creation_input_tokens","cache_creation","output_tokens"))
                    && only(n.path("cache_creation"),Set.of("ephemeral_5m_input_tokens","ephemeral_1h_input_tokens"));
                default -> false;
            };
        } catch (Exception ex) { return false; }
    }
    private static boolean only(JsonNode node, Set<String> allowed) {
        if (!node.isObject()) return false;
        var fields=node.fieldNames();
        while (fields.hasNext()) if (!allowed.contains(fields.next())) return false;
        return true;
    }
    private FakePricingEvidence() {}
}
