package io.github.samzhu.bot;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Import;

/** Explicitly installs test-owned model boundaries for application integration tests. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Import({TestChatModelConfiguration.class, TestUsageCaptureConfiguration.class})
public @interface UseTestFakes { }
