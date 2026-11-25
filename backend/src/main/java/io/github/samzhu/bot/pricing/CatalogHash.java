package io.github.samzhu.bot.pricing;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;

/** Canonical hash shared by publication and release metadata generation. */
public final class CatalogHash {
    private static final ObjectMapper json = new ObjectMapper();
    private CatalogHash() {}
    public static String contentHash(String source, List<PriceCatalog.Entry> entries) {
        try {
            var normalized=entries.stream().map(e -> {
                var p=e.snapshot();
                try {
                    var fields=new java.util.ArrayList<Object>(List.of(p.provider(),p.endpoint(),p.model(),p.channel(),p.revision(),e.fromInclusive().toString(),e.untilExclusive().toString(),new java.util.TreeMap<>(p.rates())));
                    // Preserve historical hashes when legacy entries have no metadata.
                    if (e.metadata()!=null) fields.add(e.metadata());
                    return json.writeValueAsString(fields);
                }
                catch (Exception ex) { throw new IllegalArgumentException(ex); }
            }).sorted().toList();
            byte[] bytes=json.writeValueAsBytes(List.of(source,normalized));
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch(Exception e) { throw new IllegalArgumentException("Invalid catalog",e); }
    }
}
