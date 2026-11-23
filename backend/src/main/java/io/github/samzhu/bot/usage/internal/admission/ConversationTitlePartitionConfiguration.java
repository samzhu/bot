package io.github.samzhu.bot.usage.internal.admission;

import io.github.samzhu.bot.usage.ConversationTitlePartition;
import io.github.samzhu.bot.usage.WorkspaceAdmission;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
class ConversationTitlePartitionConfiguration {
    @Bean ConversationTitlePartition activeTitlePartition(Environment environment) {
        return () -> "google-genai".equals(environment.getProperty("spring.ai.model.chat")) ? WorkspaceAdmission.Partition.REAL : WorkspaceAdmission.Partition.SIMULATION;
    }
}
