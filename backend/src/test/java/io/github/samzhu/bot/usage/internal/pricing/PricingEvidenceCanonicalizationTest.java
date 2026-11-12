package io.github.samzhu.bot.usage.internal.pricing;

import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class PricingEvidenceCanonicalizationTest {
    @Test void equivalentPayloadHasSameEvidenceRegardlessOfConstructionOrder() {
        Map<String,Object> first = new LinkedHashMap<>();
        first.put("category", "input"); first.put("nativeQuantity", "9007199254740993");
        first.put("exactAmount", "0.0000000000001"); first.put("reason", "PRECISION_UNSUPPORTED");
        Map<String,Object> reversed = new LinkedHashMap<>();
        reversed.put("reason", "PRECISION_UNSUPPORTED"); reversed.put("exactAmount", "0.0000000000001");
        reversed.put("nativeQuantity", "9007199254740993"); reversed.put("category", "input");
        String a = PricingPublisher.canonicalDetails(List.of("NOT_FINAL", "COVERAGE_UNKNOWN"), List.of(first));
        String b = PricingPublisher.canonicalDetails(List.of("COVERAGE_UNKNOWN", "NOT_FINAL"), List.of(reversed));
        assertEquals(a, b);
        assertNotEquals(a, PricingPublisher.canonicalDetails(List.of("COVERAGE_UNKNOWN"), List.of(reversed)));
        assertTrue(a.contains("9007199254740993"));
        assertTrue(a.contains("0.0000000000001"));
    }
    @Test void hashIncludesMoneyAndIdentityAndIgnoresMapConstructionOrder() {
        var one=new LinkedHashMap<String,Object>();
        one.put("attemptId","a"); one.put("knownSubtotal","0.001000000000"); one.put("completeAmount",null); one.put("sourceVersion","7");
        var reversed=new LinkedHashMap<String,Object>();
        reversed.put("sourceVersion","7"); reversed.put("completeAmount",null); reversed.put("knownSubtotal","0.001000000000"); reversed.put("attemptId","a");
        assertEquals(PricingPublisher.canonicalPayloadHash(one),PricingPublisher.canonicalPayloadHash(reversed));
        reversed.put("knownSubtotal","0.002000000000");
        assertNotEquals(PricingPublisher.canonicalPayloadHash(one),PricingPublisher.canonicalPayloadHash(reversed));
        reversed.put("knownSubtotal","0.001000000000"); reversed.put("sourceVersion","8");
        assertNotEquals(PricingPublisher.canonicalPayloadHash(one),PricingPublisher.canonicalPayloadHash(reversed));
    }
}
