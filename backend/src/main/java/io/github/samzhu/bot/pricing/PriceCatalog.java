package io.github.samzhu.bot.pricing;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Selects one controlled revision by dispatch time; ambiguity never picks a winner. */
public final class PriceCatalog {
    public record Entry(PriceSnapshot snapshot, Instant fromInclusive, Instant untilExclusive) {
        public Entry {
            Objects.requireNonNull(snapshot); Objects.requireNonNull(fromInclusive); Objects.requireNonNull(untilExclusive);
            if (!fromInclusive.isBefore(untilExclusive)) throw new IllegalArgumentException("empty price interval");
        }
    }
    public enum State { MATCHED, MISSING, OVERLAPPING }
    public record Selection(State state, PriceSnapshot snapshot) {}
    public Selection select(List<Entry> entries, String provider, String endpoint, String model, String channel, Instant at) {
        var matches = entries.stream().filter(e -> e.snapshot().provider().equals(provider)
                && e.snapshot().endpoint().equals(endpoint) && e.snapshot().model().equals(model)
                && e.snapshot().channel().equals(channel) && !at.isBefore(e.fromInclusive()) && at.isBefore(e.untilExclusive())).toList();
        return matches.isEmpty() ? new Selection(State.MISSING, null)
                : matches.size() == 1 ? new Selection(State.MATCHED, matches.getFirst().snapshot())
                : new Selection(State.OVERLAPPING, null);
    }
}
