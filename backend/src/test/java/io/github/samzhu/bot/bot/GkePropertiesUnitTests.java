package io.github.samzhu.bot.bot;

import io.github.samzhu.bot.bot.internal.GkeProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GkePropertiesUnitTests {

    @Test
    @DisplayName("GkeProperties preserves an explicit OFF mode")
    void testExplicitOffMode() {
        GkeProperties offProps = new GkeProperties(GkeProperties.ExecutionMode.OFF);
        assertFalse(offProps.isSimulated());
        assertEquals(GkeProperties.ExecutionMode.OFF, offProps.getExecutionMode());
    }

    @Test
    @DisplayName("GkeProperties defaults to OFF when given null")
    void testNullDefaultsToOff() {
        GkeProperties props = new GkeProperties(null);
        assertFalse(props.isSimulated());
        assertEquals(GkeProperties.ExecutionMode.OFF, props.getExecutionMode());
    }
}
