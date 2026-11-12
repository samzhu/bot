package io.github.samzhu.bot.usage;

import java.util.List;
import java.util.Map;

/** Server-side boundary; callers enforce the relevant workspace/actor authorization. */
public interface UsageCostOperations {
    record LineItem(String category, String nativeQuantity, String unit, String meaning,
                    String rate, String rateBasis, String exactAmount, String storedAmount, List<String> reasons) {}
    record Cost(String attemptId, String status, String knownSubtotal, String completeAmount,
                String currency, boolean simulation, boolean needsReprice, String sourceProjectionVersion,
                String estimateSourceVersion, String currentRevision, String priceRevision,
                String priceSnapshotId, String priceSourceReference, String priceEffectiveFrom, String priceEffectiveUntil,
                String calculatorVersion, String sourceSnapshotId, String sourceLayer, String schemaVersion,
                String adapterVersion, String captureVersion, String captureCompleteness,
                String semantic, String ordinal, List<String> reasons, List<LineItem> lineItems) {}
    Map<String, Cost> readCosts(List<String> attemptIds);
}
