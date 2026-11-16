package io.github.samzhu.bot.pricing;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiPriceReferenceTest {
    @Test
    void publishedReferenceCanBeReadWithoutActivatingACatalog() throws Exception {
        var resource = new ClassPathResource("pricing-reference/openai-gpt-5-mini.yaml");
        assertThat(resource.exists()).as("controlled GPT-5 mini price reference is packaged").isTrue();
        var source = new YamlPropertySourceLoader().load("reference", resource).getFirst();
        assertThat(source.getProperty("model")).isEqualTo("gpt-5-mini");
        assertThat(source.getProperty("unit")).isEqualTo("USD_PER_1000000_TOKENS");
        assertThat(source.getProperty("source")).isEqualTo("https://developers.openai.com/api/docs/models/gpt-5-mini.md");
        assertThat(source.getProperty("release-source")).isEqualTo("https://openai.com/index/introducing-gpt-5-for-developers/");
        assertThat(source.getProperty("verified-date")).isEqualTo("2026-11-16");
        assertThat(source.getProperty("pricing-scope")).isEqualTo("GENERAL_API_REFERENCE");
        assertThat(source.getProperty("tier")).isEqualTo("NOT_EXPLICITLY_SPECIFIED");
        assertThat(source.getProperty("cache-write")).isEqualTo("UNKNOWN");
        assertThat(source.getProperty("model-status")).isEqualTo("DEPRECATED");
        assertThat(source.getProperty("snapshot")).isEqualTo("gpt-5-mini-2025-08-07");
        assertThat(source.getProperty("rates.input")).isEqualTo("0.25");
        assertThat(source.getProperty("rates.cache_read")).isEqualTo("0.025");
        assertThat(source.getProperty("rates.output")).isEqualTo("2");
    }
}
