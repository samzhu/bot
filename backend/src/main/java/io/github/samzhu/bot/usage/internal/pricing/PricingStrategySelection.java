package io.github.samzhu.bot.usage.internal.pricing;

import io.github.samzhu.bot.pricing.PricingCalculator;
import io.github.samzhu.bot.usage.UsageLedger;
import java.util.Set;

/** Selects only native contracts for which the persisted evidence has a known interpretation. */
final class PricingStrategySelection {
    record Strategy(String provider, String endpoint, boolean extraCostsAbsent, String version) {}

    static Strategy select(UsageLedger.PricingInputView input) {
        var snapshot = input.effectiveSnapshot();
        if (snapshot == null) return null;
        var fixture = FakePricingEvidence.dialect(input);
        if (fixture != null && "1".equals(snapshot.adapterVersion()) && "1".equals(snapshot.captureVersion()))
            return new Strategy(fixture.provider(), fixture.endpoint(), fixture.textOnlyEvidence(), PricingCalculator.VERSION);
        boolean google = "GOOGLE".equals(input.provider()) && "GEMINI_API".equals(input.channel());
        if (!google || !"SDK_NATIVE".equals(snapshot.sourceLayer()) || !"1".equals(snapshot.captureVersion())) return null;
        if ("generateContent".equals(input.endpoint())
                && (("1".equals(snapshot.schemaVersion()) && Set.of("1", "2.1.0-RC2", "2.1.0").contains(snapshot.adapterVersion()))
                    || ("google-v1".equals(snapshot.schemaVersion()) && "1".equals(snapshot.adapterVersion()))))
            return new Strategy("gemini", "generateContent", false, PricingCalculator.VERSION);
        if ("CONVERSATION_TITLE".equals(input.purpose()) && "interactions".equals(input.endpoint())
                && "gemini-3.5-flash-lite".equals(input.model()) && "interactions-v1".equals(snapshot.schemaVersion())
                && "title-interactions-v1".equals(snapshot.adapterVersion()))
            return new Strategy("gemini", "interactions", true, PricingCalculator.VERSION);
        return null;
    }
    private PricingStrategySelection() {}
}
