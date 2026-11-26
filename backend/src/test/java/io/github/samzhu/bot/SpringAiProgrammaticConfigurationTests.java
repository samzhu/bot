package io.github.samzhu.bot;

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
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

@BackendIntegrationTest
class SpringAiProgrammaticConfigurationTests {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private ChatModel chatModel;

    @Autowired
    private StreamingChatModel streamingChatModel;

    @Autowired
    private BackendTestChatModel testChatModel;

    @Autowired
    private SessionService sessionService;

    @Autowired
    private SessionRepository sessionRepository;

    @Test
    @DisplayName("ChatModel and StreamingChatModel resolve to the same test model")
    void chatInterfacesResolveToTheSameTestModel() {
        assertThat(chatModel).isSameAs(testChatModel);
        assertThat(streamingChatModel).isSameAs(testChatModel);
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
