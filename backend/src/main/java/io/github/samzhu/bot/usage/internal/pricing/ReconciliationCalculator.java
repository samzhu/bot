package io.github.samzhu.bot.usage.internal.pricing;

import io.github.samzhu.bot.pricing.PriceSnapshot;
import io.github.samzhu.bot.pricing.PricingCalculator;
import io.github.samzhu.bot.usage.UsageLedger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Applies the same evidence limits as dispatch pricing to an explicitly selected price. */
final class ReconciliationCalculator {
    record Result(PricingCalculator.Estimate estimate, String status, String details) {}

    static Result calculate(UsageLedger.PricingInputView input, String priceRevision, Map<String,String> rates) {
        var snapshot=input.effectiveSnapshot();
        if (!"VALID".equals(input.projectionState()) || snapshot==null || !"JSONB".equals(snapshot.rawState()) || snapshot.rawJson()==null)
            throw new JdbcUsageCostRevisionOperations.Failure("SOURCE_UNAVAILABLE");
        var fixture=FakePricingEvidence.dialect(input);
        boolean google="GOOGLE".equals(input.provider()) && "generateContent".equals(input.endpoint()) && "GEMINI_API".equals(input.channel());
        if (!google && fixture==null) throw new JdbcUsageCostRevisionOperations.Failure("UNSUPPORTED_SOURCE");
        String provider=fixture==null?"gemini":fixture.provider();
        String endpoint=fixture==null?"generateContent":fixture.endpoint();
        var price=priceRevision==null?null:new PriceSnapshot(provider,endpoint,input.model(),input.channel(),priceRevision,rates);
        var estimate=new PricingCalculator().estimate(new PricingCalculator.Input(provider,endpoint,input.model(),input.channel(),snapshot.rawJson(),
                "FINAL".equals(snapshot.semantic()),"VERIFIED".equals(input.attemptCoverage()),fixture!=null && fixture.textOnlyEvidence()),price);
        List<String> reasons=new ArrayList<>();
        estimate.reasons().forEach(r -> reasons.add(r.name()));
        if (!"COMPLETE".equals(snapshot.captureCompleteness())) reasons.add("CAPTURE_INCOMPLETE");
        java.util.Collections.sort(reasons);
        String status=reasons.isEmpty()?estimate.status().name():estimate.knownSubtotal()==null?"UNKNOWN":"PARTIAL";
        List<Map<String,Object>> lines=new ArrayList<>();
        for(var line:estimate.lines()) {
            Map<String,Object> value=new LinkedHashMap<>();
            value.put("category",line.category()); value.put("nativeQuantity",line.nativeQuantity());
            value.put("ratePerMillion",line.ratePerMillion()); value.put("exactAmount",line.exactAmount());
            value.put("storedAmount",line.storedAmount()==null?null:line.storedAmount().toPlainString()); value.put("reason",line.reason());
            lines.add(value);
        }
        return new Result(estimate,status,PricingPublisher.canonicalDetails(reasons,lines));
    }
    static Result withPriceOverlap(Result result) {
        try {
            var json=new com.fasterxml.jackson.databind.ObjectMapper();
            var root=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(result.details());
            var reasons=(com.fasterxml.jackson.databind.node.ArrayNode)root.get("reasons");
            reasons.add("OVERLAPPING_PRICE");
            return new Result(result.estimate(),result.status(),json.writeValueAsString(root));
        } catch (java.io.IOException ex) { throw new IllegalStateException(ex); }
    }
    private ReconciliationCalculator() {}
}
