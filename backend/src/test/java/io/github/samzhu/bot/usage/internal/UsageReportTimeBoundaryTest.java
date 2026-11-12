package io.github.samzhu.bot.usage.internal;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.assertEquals;

class UsageReportTimeBoundaryTest {
    @Test void exclusiveNanosecondBoundaryIncludesOnlyEarlierPostgresMicroseconds() {
        assertEquals(Instant.parse("2026-11-13T04:00:00.000001Z"), UsageReportService.postgresExclusiveUpperBound(Instant.parse("2026-11-13T04:00:00.000000123Z")));
        assertEquals(Instant.parse("2026-11-13T04:00:00.000001Z"), UsageReportService.postgresExclusiveUpperBound(Instant.parse("2026-11-13T04:00:00.000001Z")));
        assertEquals(Instant.parse("2026-11-13T04:00:01Z"), UsageReportService.postgresExclusiveUpperBound(Instant.parse("2026-11-13T04:00:00.999999999Z")));
    }
}
