package io.github.samzhu.bot.usage.internal.admission;

import io.github.samzhu.bot.usage.ConversationTitlePartition;
import io.github.samzhu.bot.usage.WorkspaceAdmission;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;

class ConversationTitlePartitionConfigurationTests {
    @Test void onlyExplicitGeminiUsesRealPartitionEvenWhenKeyExists() {
        var runner = new ApplicationContextRunner().withUserConfiguration(ConversationTitlePartitionConfiguration.class);
        runner.withPropertyValues("bot-google-api-key=synthetic-key-only").run(context ->
                assertThat(context.getBean(ConversationTitlePartition.class).current()).isEqualTo(WorkspaceAdmission.Partition.SIMULATION));
        runner.withPropertyValues("spring.ai.model.chat=google-genai").run(context ->
                assertThat(context.getBean(ConversationTitlePartition.class).current()).isEqualTo(WorkspaceAdmission.Partition.REAL));
    }
    @Test void standardGeminiSelectorUsesRealPartition() {
        new ApplicationContextRunner().withUserConfiguration(ConversationTitlePartitionConfiguration.class)
            .withPropertyValues("spring.ai.model.chat=google-genai")
            .run(context -> assertThat(context.getBean(ConversationTitlePartition.class).current())
                .isEqualTo(WorkspaceAdmission.Partition.REAL));
    }
}
