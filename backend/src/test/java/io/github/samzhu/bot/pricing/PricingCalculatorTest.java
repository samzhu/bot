package io.github.samzhu.bot.pricing;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class PricingCalculatorTest {
    private final PricingCalculator calculator = new PricingCalculator();
    private PriceSnapshot prices(String provider, String endpoint, Map<String, String> rates) {
        return new PriceSnapshot(provider, endpoint, "fixture-model", "fixture-channel", "test-only", rates);
    }
    private PricingCalculator.Input input(String provider, String endpoint, String json, boolean finalUsage, boolean verified, boolean extraKnownAbsent) {
        return new PricingCalculator.Input(provider, endpoint, "fixture-model", "fixture-channel", json, finalUsage, verified, extraKnownAbsent);
    }
    @Test void threeProviderFixtures() {
        var open = calculator.estimate(input("openai", "responses-fixture-ttl", """
          {"input_tokens":1000,"input_tokens_details":{"cached_tokens":200,"cache_write_tokens":300,"cache_write":{"30m":100,"12h":200}},"output_tokens":400,"output_tokens_details":{"reasoning_tokens":100}}
          """, true,true,true), prices("openai", "responses-fixture-ttl", Map.of("input","1","cache_read","0.1","cache_write_30m","1.25","cache_write_12h","2.5","output","2")));
        var anth = calculator.estimate(input("anthropic", "messages", """
          {"input_tokens":500,"cache_read_input_tokens":200,"cache_creation_input_tokens":300,"cache_creation":{"ephemeral_5m_input_tokens":100,"ephemeral_1h_input_tokens":200},"output_tokens":400}
          """,true,true,true), prices("anthropic", "messages", Map.of("input","1","cache_read","0.1","cache_write_5m","1.25","cache_write_1h","2","output","2")));
        var gem = calculator.estimate(input("gemini", "generateContent", """
          {"promptTokenCount":1000,"cachedContentTokenCount":200,"candidatesTokenCount":300,"thoughtsTokenCount":100,"toolUsePromptTokenCount":0,"totalTokenCount":1400}
          """,true,true,true), prices("gemini", "generateContent", Map.of("input","1","cache_read","0.1","output","2","thoughts","2","tool_input","1")));
        assertEquals(new BigDecimal("0.001945000000"), open.completeAmount());
        assertEquals(new BigDecimal("0.001845000000"), anth.completeAmount());
        assertEquals(new BigDecimal("0.001620000000"), gem.completeAmount());
        assertEquals(PricingCalculator.Status.COMPLETE, open.status());
        assertEquals(PricingCalculator.Status.COMPLETE, anth.status());
        assertEquals(PricingCalculator.Status.COMPLETE, gem.status());
    }
    @Test void missingNullInconsistentAndUnverifiedAreNeverComplete() {
        var p = prices("anthropic", "messages", Map.of("input","1","cache_read","1","cache_write_5m","1","cache_write_1h","1","output","1"));
        var missing=calculator.estimate(input("anthropic","messages","{\"input_tokens\":0,\"output_tokens\":null}",true,true,true),p);
        assertNotEquals(PricingCalculator.Status.COMPLETE,missing.status());
        assertNull(missing.completeAmount());
        assertNotNull(missing.knownSubtotal());
        assertTrue(missing.reasons().contains(PricingCalculator.Reason.MISSING_USAGE));
        var inconsistent=calculator.estimate(input("anthropic","messages","{\"input_tokens\":1,\"output_tokens\":1,\"cache_read_input_tokens\":0,\"cache_creation_input_tokens\":2,\"cache_creation\":{\"ephemeral_5m_input_tokens\":2,\"ephemeral_1h_input_tokens\":2}}",true,true,true),p);
        assertTrue(inconsistent.reasons().contains(PricingCalculator.Reason.INVALID_USAGE));
        var unverified=calculator.estimate(input("anthropic","messages","{\"input_tokens\":0,\"output_tokens\":0,\"cache_read_input_tokens\":0,\"cache_creation_input_tokens\":0,\"cache_creation\":{\"ephemeral_5m_input_tokens\":0,\"ephemeral_1h_input_tokens\":0}}",false,false,false),p);
        assertTrue(unverified.reasons().containsAll(java.util.Set.of(PricingCalculator.Reason.NOT_FINAL, PricingCalculator.Reason.COVERAGE_UNKNOWN, PricingCalculator.Reason.EXTRA_COST_UNKNOWN)));
        assertNotEquals(PricingCalculator.Status.COMPLETE,unverified.status());
        assertNull(unverified.completeAmount());
        assertNotNull(unverified.knownSubtotal());
    }
    @Test void precisionAndMismatchedPriceAreUnknown() {
        var tiny = prices("gemini","generateContent",Map.of("input","0.0000001","cache_read","0","output","0","thoughts","0","tool_input","0"));
        var raw="{\"promptTokenCount\":1,\"cachedContentTokenCount\":0,\"candidatesTokenCount\":0,\"thoughtsTokenCount\":0,\"toolUsePromptTokenCount\":0}";
        var result=calculator.estimate(input("gemini","generateContent",raw,true,true,true),tiny);
        assertTrue(result.reasons().contains(PricingCalculator.Reason.PRECISION_UNSUPPORTED));
        assertNotEquals(PricingCalculator.Status.COMPLETE,result.status());
        assertNull(result.completeAmount());
        assertTrue(result.lines().stream().anyMatch(l -> "0.0000000000001".equals(l.exactAmount()) && l.storedAmount()==null));
        var mismatch=calculator.estimate(input("gemini","generateContent",raw,true,true,true),prices("gemini","generateContent",Map.of()));
        assertTrue(mismatch.reasons().contains(PricingCalculator.Reason.MISSING_PRICE));
    }
    @Test void realResponsesUsesAggregateAndNeverRequiresSyntheticTtl() {
        var result=calculator.estimate(input("openai","responses", """
          {"input_tokens":1000,"input_tokens_details":{"cached_tokens":200,"cache_write_tokens":300},"output_tokens":400}
          """,true,true,true),prices("openai","responses",Map.of("input","1","cache_read","0.1","cache_write","1.25","output","2")));
        assertEquals(new BigDecimal("0.001695000000"),result.completeAmount());
        assertEquals(PricingCalculator.Status.COMPLETE,result.status());
    }
    @Test void overlappingPricesNeverChooseAndChannelsDoNotMix() {
        var catalog=new PriceCatalog();
        var p=prices("gemini","generateContent",Map.of());
        var start=java.time.Instant.parse("2026-01-01T00:00:00Z");
        var end=java.time.Instant.parse("2027-01-01T00:00:00Z");
        var e=new PriceCatalog.Entry(p,start,end);
        assertEquals(PriceCatalog.State.OVERLAPPING,catalog.select(java.util.List.of(e,e),"gemini","generateContent","fixture-model","fixture-channel",start).state());
        assertEquals(PriceCatalog.State.MISSING,catalog.select(java.util.List.of(e),"gemini","generateContent","fixture-model","other",start).state());
        assertEquals(PriceCatalog.State.MISSING,catalog.select(java.util.List.of(e),"gemini","generateContent","fixture-model","fixture-channel",end).state());
    }
    @Test void overflowingTotalAndLineRoundingAreNotSilentlyComplete() {
        var raw="{\"input_tokens\":999999999999999999999999,\"cache_read_input_tokens\":0,\"cache_creation_input_tokens\":0,\"cache_creation\":{\"ephemeral_5m_input_tokens\":0,\"ephemeral_1h_input_tokens\":0},\"output_tokens\":0}";
        var result=calculator.estimate(input("anthropic","messages",raw,true,true,true),prices("anthropic","messages",Map.of("input","1000000000","cache_read","0","cache_write_5m","0","cache_write_1h","0","output","0")));
        assertTrue(result.reasons().contains(PricingCalculator.Reason.PRECISION_UNSUPPORTED));
        assertNotEquals(PricingCalculator.Status.COMPLETE,result.status());
    }
    @Test void halfEvenRoundsEachStoredLineBeforeSumming() {
        var raw="{\"input_tokens\":1,\"cache_read_input_tokens\":1,\"cache_creation_input_tokens\":0,\"cache_creation\":{\"ephemeral_5m_input_tokens\":0,\"ephemeral_1h_input_tokens\":0},\"output_tokens\":0}";
        var result=calculator.estimate(input("anthropic","messages",raw,true,true,true),prices("anthropic","messages",Map.of("input","0.0000015","cache_read","0.0000015","cache_write_5m","0","cache_write_1h","0","output","0")));
        assertEquals(new BigDecimal("0.000000000004"),result.completeAmount());
        assertEquals(PricingCalculator.Status.COMPLETE,result.status());
    }
    @Test void providerAndChannelMismatchNeverCalculate() {
        var result=calculator.estimate(input("gemini","generateContent","{}",true,true,true),prices("anthropic","messages",Map.of()));
        assertEquals(PricingCalculator.Status.UNKNOWN,result.status());
        assertNull(result.completeAmount());
        assertNull(result.knownSubtotal());
        assertTrue(result.reasons().contains(PricingCalculator.Reason.PRICE_MISMATCH));
    }
    @Test void missingPriceExposesUnpricedPartsWithoutFakeAmounts() {
        var raw="{\"promptTokenCount\":1000,\"cachedContentTokenCount\":200,\"candidatesTokenCount\":300,\"thoughtsTokenCount\":100,\"toolUsePromptTokenCount\":0}";
        var result=calculator.estimate(input("gemini","generateContent",raw,true,true,true),null);
        assertEquals(PricingCalculator.Status.UNKNOWN,result.status());
        assertNull(result.knownSubtotal()); assertNull(result.completeAmount());
        assertTrue(result.lines().stream().anyMatch(l -> "800".equals(l.nativeQuantity()) && "MISSING_PRICE".equals(l.reason()) && l.exactAmount()==null));
    }
    @Test void inconsistentParentDoesNotProduceTrustedChildSubtotal() {
        var raw="{\"promptTokenCount\":1,\"cachedContentTokenCount\":2,\"candidatesTokenCount\":0,\"thoughtsTokenCount\":0,\"toolUsePromptTokenCount\":0}";
        var result=calculator.estimate(input("gemini","generateContent",raw,true,true,true),prices("gemini","generateContent",Map.of("input","1","cache_read","1","output","0","thoughts","0","tool_input","0")));
        assertTrue(result.reasons().contains(PricingCalculator.Reason.INVALID_USAGE));
        assertNull(result.completeAmount());
        assertTrue(result.lines().stream().noneMatch(l -> "cache_read".equals(l.category())));
    }
}
