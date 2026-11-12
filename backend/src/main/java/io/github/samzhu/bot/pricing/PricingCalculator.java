package io.github.samzhu.bot.pricing;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Pure, versioned native-usage calculator. Rate values are USD per million units. */
public final class PricingCalculator {
    public static final String VERSION = "1";
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private static final BigDecimal MILLION = new BigDecimal("1000000");
    private static final BigDecimal LIMIT = BigDecimal.TEN.pow(18);

    /** finalUsage and coverage must come from evidence, never stream completion. */
    public record Input(String provider, String endpoint, String model, String channel, String rawJson,
                        boolean finalUsage, boolean attemptCoverageVerified, boolean extraCostsKnownAbsent) {}
    public enum Status { COMPLETE, PARTIAL, UNKNOWN }
    public enum Reason { MISSING_USAGE, INVALID_USAGE, UNSUPPORTED_ENDPOINT, MISSING_PRICE,
        PRICE_MISMATCH, PRECISION_UNSUPPORTED, NOT_FINAL, COVERAGE_UNKNOWN, EXTRA_COST_UNKNOWN }
    public record LineItem(String category, String nativeQuantity, String ratePerMillion,
                           String exactAmount, BigDecimal storedAmount, String reason) {}
    /** COMPLETE covers only the declared estimate scope; it is not an invoice. */
    public record Estimate(Status status, BigDecimal knownSubtotal, BigDecimal completeAmount, String currency, String priceRevision,
                           String calculatorVersion, List<LineItem> lines, Set<Reason> reasons) {
        public Estimate { lines = List.copyOf(lines); reasons = Set.copyOf(reasons); }
    }

    public Estimate estimate(Input input, PriceSnapshot price) {
        var ctx = new Context(price);
        if (!input.finalUsage()) ctx.reasons.add(Reason.NOT_FINAL);
        if (!input.attemptCoverageVerified()) ctx.reasons.add(Reason.COVERAGE_UNKNOWN);
        if (!input.extraCostsKnownAbsent()) ctx.reasons.add(Reason.EXTRA_COST_UNKNOWN);
        if (price == null) ctx.reasons.add(Reason.MISSING_PRICE);
        if (price != null && (!price.provider().equals(input.provider()) || !price.endpoint().equals(input.endpoint())
                || !price.model().equals(input.model()) || !price.channel().equals(input.channel()))) {
            ctx.reasons.add(Reason.PRICE_MISMATCH); return ctx.result();
        }
        JsonNode raw;
        try { raw = input.rawJson() == null ? null : JSON.readTree(input.rawJson()); }
        catch (Exception ex) { ctx.reasons.add(Reason.INVALID_USAGE); return ctx.result(); }
        if (raw == null || !raw.isObject()) { ctx.reasons.add(Reason.MISSING_USAGE); return ctx.result(); }
        switch (input.provider() + ":" + input.endpoint()) {
            case "openai:responses" -> openAi(raw, ctx, false);
            case "openai:responses-fixture-ttl" -> openAi(raw, ctx, true);
            case "anthropic:messages" -> anthropic(raw, ctx);
            case "gemini:generateContent" -> gemini(raw, ctx);
            default -> ctx.reasons.add(Reason.UNSUPPORTED_ENDPOINT);
        }
        return ctx.result();
    }

    private static void openAi(JsonNode raw, Context c, boolean fixtureTtl) {
        var input = c.count(raw, "input_tokens");
        var read = c.count(raw, "input_tokens_details", "cached_tokens");
        var write = c.count(raw, "input_tokens_details", "cache_write_tokens");
        var shortTtl = fixtureTtl ? c.count(raw, "input_tokens_details", "cache_write", "30m") : null;
        var longTtl = fixtureTtl ? c.count(raw, "input_tokens_details", "cache_write", "12h") : null;
        var output = c.count(raw, "output_tokens");
        var reasoning = c.optionalCount(raw, "output_tokens_details", "reasoning_tokens");
        if (input != null && read != null && write != null) {
            var ordinary = input.subtract(read).subtract(write);
            if (ordinary.signum() < 0) c.reasons.add(Reason.INVALID_USAGE);
            else {
                c.line("input", ordinary);
                c.line("cache_read", read);
                if (fixtureTtl) c.ttl(write, shortTtl, longTtl, "cache_write_30m", "cache_write_12h");
                else c.line("cache_write", write);
            }
        }
        if (output != null && reasoning != null && reasoning.compareTo(output) > 0) c.reasons.add(Reason.INVALID_USAGE);
        else c.line("output", output);
    }
    private static void anthropic(JsonNode raw, Context c) {
        var input = c.count(raw, "input_tokens");
        var read = c.count(raw, "cache_read_input_tokens");
        var write = c.count(raw, "cache_creation_input_tokens");
        var shortTtl = c.count(raw, "cache_creation", "ephemeral_5m_input_tokens");
        var longTtl = c.count(raw, "cache_creation", "ephemeral_1h_input_tokens");
        var output = c.count(raw, "output_tokens");
        c.line("input", input); c.line("cache_read", read);
        c.ttl(write, shortTtl, longTtl, "cache_write_5m", "cache_write_1h");
        c.line("output", output);
    }
    private static void gemini(JsonNode raw, Context c) {
        var prompt = c.count(raw, "promptTokenCount");
        var cached = c.count(raw, "cachedContentTokenCount");
        var output = c.count(raw, "candidatesTokenCount");
        var thoughts = c.count(raw, "thoughtsTokenCount");
        var tool = c.count(raw, "toolUsePromptTokenCount");
        if (prompt != null && cached != null) {
            var ordinary = prompt.subtract(cached);
            if (ordinary.signum() < 0) c.reasons.add(Reason.INVALID_USAGE);
            else { c.line("input", ordinary); c.line("cache_read", cached); }
        }
        c.line("output", output); c.line("thoughts", thoughts); c.line("tool_input", tool);
    }
    private static final class Context {
        final PriceSnapshot price;
        final List<LineItem> lines = new ArrayList<>();
        final EnumSet<Reason> reasons = EnumSet.noneOf(Reason.class);
        BigDecimal subtotal;
        Context(PriceSnapshot price) { this.price = price; }
        BigInteger count(JsonNode root, String... path) {
            JsonNode node = root;
            for (String part : path) node = node.path(part);
            if (node.isMissingNode() || node.isNull()) { reasons.add(Reason.MISSING_USAGE); return null; }
            if (!node.isIntegralNumber() || node.bigIntegerValue().signum() < 0) { reasons.add(Reason.INVALID_USAGE); return null; }
            return node.bigIntegerValue();
        }
        BigInteger optionalCount(JsonNode root, String... path) {
            JsonNode node = root;
            for (String part : path) node = node.path(part);
            return node.isMissingNode() || node.isNull() ? null : count(root, path);
        }
        void ttl(BigInteger parent, BigInteger first, BigInteger second, String a, String b) {
            if (parent == null || first == null || second == null) return;
            if (!first.add(second).equals(parent)) { reasons.add(Reason.INVALID_USAGE); return; }
            line(a, first); line(b, second);
        }
        void line(String category, BigInteger quantity) {
            if (quantity == null) { lines.add(new LineItem(category, null, null, null, null, "MISSING_USAGE")); return; }
            String rateText = price == null ? null : price.rates().get(category);
            if (rateText == null) { reasons.add(Reason.MISSING_PRICE); lines.add(new LineItem(category, quantity.toString(), null, null, null, "MISSING_PRICE")); return; }
            BigDecimal rate;
            try { rate = new BigDecimal(rateText); if (rate.signum() < 0) throw new NumberFormatException(); }
            catch (NumberFormatException e) { reasons.add(Reason.MISSING_PRICE); lines.add(new LineItem(category, quantity.toString(), null, null, null, "MISSING_PRICE")); return; }
            BigDecimal exact = new BigDecimal(quantity).multiply(rate).divide(MILLION);
            BigDecimal stored = exact.setScale(12, RoundingMode.HALF_EVEN);
            if (stored.abs().compareTo(LIMIT) >= 0 || (exact.signum() != 0 && stored.signum() == 0)) {
                reasons.add(Reason.PRECISION_UNSUPPORTED);
                lines.add(new LineItem(category, quantity.toString(), rateText, exact.toPlainString(), null, "PRECISION_UNSUPPORTED"));
                return;
            }
            lines.add(new LineItem(category, quantity.toString(), rateText, exact.toPlainString(), stored, null));
            subtotal = subtotal == null ? stored : subtotal.add(stored);
        }
        Estimate result() {
            if (subtotal != null && subtotal.abs().compareTo(LIMIT) >= 0) { reasons.add(Reason.PRECISION_UNSUPPORTED); subtotal = null; }
            Status status = reasons.isEmpty() ? Status.COMPLETE : subtotal == null ? Status.UNKNOWN : Status.PARTIAL;
            return new Estimate(status, subtotal, status == Status.COMPLETE ? subtotal : null,
                    "USD", price == null ? null : price.revision(), VERSION, lines, reasons);
        }
    }
}
