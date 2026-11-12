package io.github.samzhu.bot.usage.internal.pricing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.samzhu.bot.usage.UsageCostOperations;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class JdbcUsageCostOperations implements UsageCostOperations {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();
    public JdbcUsageCostOperations(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override
    public Map<String, Cost> readCosts(List<String> attemptIds) {
        if (attemptIds.isEmpty()) return Map.of();
        var result = new LinkedHashMap<String, Cost>();
        jdbc.query("""
            SELECT a.id,a.provider,a.simulation,a.projection_version,e.source_version,e.id AS estimate_id,
                   e.status,e.known_subtotal,e.complete_amount,e.calculator_version,e.source_snapshot_id,
                   e.details,p.price_revision,p.id AS price_snapshot_id,p.source_reference,p.effective_from,p.effective_until,s.source_layer,s.schema_version,s.adapter_version,
                   s.capture_version,s.capture_completeness,s.semantic,s.ordinal
              FROM provider_attempt a
              LEFT JOIN usage_cost_current c ON c.attempt_id=a.id
              LEFT JOIN usage_cost_estimate e ON e.id=c.estimate_id AND e.attempt_id=a.id
              LEFT JOIN usage_price_snapshot p ON p.id=e.price_snapshot_id
              LEFT JOIN usage_snapshot s ON s.id=e.source_snapshot_id AND s.attempt_id=a.id
             WHERE a.id IN (:ids)
            """, Map.of("ids", attemptIds), rs -> {
            String id = rs.getString("id");
            long version = rs.getLong("projection_version");
            Long source = (Long) rs.getObject("source_version");
            boolean missing = source == null;
            boolean stale = !missing && source.longValue() != version;
            JsonNode details;
            try { details = missing || stale ? json.createObjectNode() : json.readTree(rs.getString("details")); }
            catch (Exception ex) { throw new IllegalStateException("Invalid stored cost details", ex); }
            List<String> reasons = new ArrayList<>();
            if (stale) reasons.add("SOURCE_CHANGED");
            details.path("reasons").forEach(n -> reasons.add(n.asText()));
            List<LineItem> lines = new ArrayList<>();
            String provider=rs.getString("provider");
            details.path("lines").forEach(n -> lines.add(new LineItem(n.path("category").asText(), text(n,"nativeQuantity"),
                "token", meaning(provider,n.path("category").asText()), text(n,"ratePerMillion"), "USD per 1000000 tokens",
                text(n,"exactAmount"), text(n,"storedAmount"), text(n,"reason") == null ? List.of() : List.of(text(n,"reason")))));
            String complete = missing || stale || rs.getBigDecimal("complete_amount") == null ? null : rs.getBigDecimal("complete_amount").toPlainString();
            String subtotal = missing || stale || rs.getBigDecimal("known_subtotal") == null ? null : rs.getBigDecimal("known_subtotal").toPlainString();
            result.put(id, new Cost(id, missing ? "UNPRICED" : stale ? "UNKNOWN" : rs.getString("status"), subtotal, complete,
                "USD", rs.getBoolean("simulation"), missing || stale, Long.toString(version), source == null ? null : source.toString(),
                missing || stale ? null : rs.getString("estimate_id"), missing || stale ? null : rs.getString("price_revision"),
                missing || stale ? null : rs.getString("price_snapshot_id"), missing || stale ? null : rs.getString("source_reference"),
                missing || stale || rs.getTimestamp("effective_from")==null ? null : rs.getTimestamp("effective_from").toInstant().toString(),
                missing || stale || rs.getTimestamp("effective_until")==null ? null : rs.getTimestamp("effective_until").toInstant().toString(),
                missing || stale ? null : rs.getString("calculator_version"), missing || stale ? null : rs.getString("source_snapshot_id"),
                missing || stale ? null : rs.getString("source_layer"), missing || stale ? null : rs.getString("schema_version"),
                missing || stale ? null : rs.getString("adapter_version"), missing || stale ? null : rs.getString("capture_version"),
                missing || stale ? null : rs.getString("capture_completeness"), missing || stale ? null : rs.getString("semantic"),
                missing || stale || rs.getObject("ordinal") == null ? null : Long.toString(rs.getLong("ordinal")), reasons, lines));
        });
        return Map.copyOf(result);
    }
    private static String meaning(String provider, String category) {
        if ("GOOGLE".equals(provider)) return switch (category) {
            case "input" -> "Gemini prompt excluding cached content";
            case "cache_read" -> "Gemini cached prompt tokens";
            case "output" -> "Gemini candidate tokens";
            case "thoughts" -> "Gemini thought tokens";
            case "tool_input" -> "Gemini tool-result input tokens";
            default -> category;
        };
        return category;
    }
    private static String text(JsonNode node, String field) {
        var v = node.get(field); return v == null || v.isNull() ? null : v.asText();
    }
}
