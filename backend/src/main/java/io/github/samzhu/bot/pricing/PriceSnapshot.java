package io.github.samzhu.bot.pricing;

import java.util.Map;
import java.util.Objects;

/** Immutable USD-per-million fixture or controlled price revision; never a live price lookup. */
public record PriceSnapshot(String provider, String endpoint, String model, String channel,
                            String revision, Map<String, String> rates) {
    public PriceSnapshot {
        Objects.requireNonNull(provider); Objects.requireNonNull(endpoint); Objects.requireNonNull(model);
        Objects.requireNonNull(channel); Objects.requireNonNull(revision);
        rates = Map.copyOf(rates);
    }
}
