package io.github.samzhu.bot.bootstrap;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import liquibase.ChecksumVersion;
import liquibase.change.CheckSum;
import liquibase.change.core.RawSQLChange;
import liquibase.changelog.ChangeSet;
import liquibase.changelog.DatabaseChangeLog;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChecksumAcceptanceCompatibilityTests {
    private ChangeSet change() {
        var change = new ChangeSet("test", "test", false, false, "test.yaml", null, null, new DatabaseChangeLog());
        change.addChange(new RawSQLChange("select 1"));
        return change;
    }
    private com.fasterxml.jackson.databind.node.ObjectNode metadata(ChangeSet change) {
        var row = JsonNodeFactory.instance.objectNode(); var sums = row.putObject("checksums");
        for (var version : ChecksumVersion.values()) {
            change.clearCheckSum(); sums.put(Integer.toString(version.getVersion()), change.generateCheckSum(version).toString());
        }
        var allowed = row.putArray("valid"); change.getValidCheckSums().forEach(c -> allowed.add(c.toString()));
        return row;
    }
    private void compare(ChangeSet change, String stored) {
        change.clearCheckSum();
        assertEquals(change.isCheckSumValid(CheckSum.parse(stored)), ReleaseSchema.validChecksum(metadata(change), stored), stored);
    }
    @Test void supportedVersionsAndAllowancesMatchVendor() {
        var base = change(); var baseline = metadata(base);
        for (var version : ChecksumVersion.values()) {
            var generated = baseline.path("checksums").path(Integer.toString(version.getVersion())).asText();
            compare(change(), generated);
            compare(change(), version.getVersion() + ":00000000000000000000000000000000");
        }
        var allowed = change(); allowed.addValidCheckSum("8:00000000000000000000000000000000");
        compare(allowed, "8:00000000000000000000000000000000");
        var generatedIsAllowed = change(); generatedIsAllowed.addValidCheckSum(baseline.path("checksums").path("8").asText());
        compare(generatedIsAllowed, "8:00000000000000000000000000000000");
        var wildcard = change(); wildcard.addValidCheckSum("any");
        compare(wildcard, "9:00000000000000000000000000000000");
    }
    @Test void blankAndMalformedChecksumsFailClosed() {
        var row = metadata(change());
        for (String value : new String[]{"", "  ", "9:", "9:abc", "9:abc!", "garbage"})
            assertFalse(ReleaseSchema.validChecksum(row, value), value);
        var wildcard = change(); wildcard.addValidCheckSum("any");
        assertFalse(ReleaseSchema.validChecksum(metadata(wildcard), "9:abc"));
    }
}
