package io.github.samzhu.bot;

import io.github.samzhu.bot.execution.internal.model.MockChatModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.audio.transcription.TranscriptionModel;
import org.springframework.ai.audio.tts.TextToSpeechModel;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.session.DefaultSessionService;
import org.springframework.ai.session.SessionRepository;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.jdbc.JdbcSessionRepository;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verification for Spring AI 2.1.0-RC2 programmatic configuration and auto-configuration isolation.
 *
 * WHY / ARCHITECTURAL INVARIANT:
 * 1. Confirms that shared Spring AI interfaces (ChatModel, StreamingChatModel, SessionService, SessionRepository)
 *    are cleanly resolved to deterministic local implementations (MockChatModel, DefaultSessionService, JdbcSessionRepository).
 * 2. Confirms that replacing starters with raw modules (spring-ai-pgvector-store, spring-ai-google-genai-tts)
 *    prevents any accidental auto-configured beans (GoogleGenAiChatModel, VectorStore, TextToSpeechModel, TranscriptionModel)
 *    from entering the application context in the non-real-ai profile.
 * 3. Confirms that no spring.autoconfigure.exclude entries are needed to suppress auto-configurations.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
class SpringAiProgrammaticConfigurationTests {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private ChatModel chatModel;

    @Autowired
    private StreamingChatModel streamingChatModel;

    @Autowired
    private SessionService sessionService;

    @Autowired
    private SessionRepository sessionRepository;

    @Test
    @DisplayName("Interface Seam: Shared ChatModel and StreamingChatModel are bound to deterministic MockChatModel under test profile")
    void chatModelBoundToMockChatModel() {
        assertThat(chatModel)
                .isNotNull()
                .isInstanceOf(MockChatModel.class);
        assertThat(streamingChatModel)
                .isNotNull()
                .isInstanceOf(MockChatModel.class);
    }

    @Test
    @DisplayName("Interface Seam: SessionService and SessionRepository are bound to DefaultSessionService and JdbcSessionRepository")
    void sessionServiceBoundToJdbcSessionService() {
        assertThat(sessionService)
                .isNotNull()
                .isInstanceOf(DefaultSessionService.class);
        assertThat(sessionRepository)
                .isNotNull()
                .isInstanceOf(JdbcSessionRepository.class);
    }

    @Test
    @DisplayName("Auto-Configuration Isolation: No unexpected GoogleGenAiChatModel bean exists under test profile")
    void noGoogleGenAiChatModelAutoConfigured() {
        String[] beanNames = applicationContext.getBeanNamesForType(GoogleGenAiChatModel.class);
        assertThat(beanNames).isEmpty();
    }

    @Test
    @DisplayName("Auto-Configuration Isolation: Inactive capabilities (VectorStore, TTS, Transcription) have zero auto-configured beans")
    void inactiveCapabilitiesHaveZeroAutoConfiguredBeans() {
        assertThat(applicationContext.getBeanNamesForType(VectorStore.class)).isEmpty();
        assertThat(applicationContext.getBeanNamesForType(TextToSpeechModel.class)).isEmpty();
        assertThat(applicationContext.getBeanNamesForType(TranscriptionModel.class)).isEmpty();
    }
}
