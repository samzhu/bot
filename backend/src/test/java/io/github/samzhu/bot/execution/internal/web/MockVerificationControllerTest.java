package io.github.samzhu.bot.execution.internal.web;

import io.github.samzhu.bot.execution.internal.model.MockChatModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MockVerificationControllerTest {

    @Test
    @DisplayName("Returns 200 with SAFE_MOCK marker when environment is local without real-ai and ChatModel is MockChatModel")
    void returnsSafeMockWhenLocalAndMockChatModel() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("local");

        @SuppressWarnings("unchecked")
        ObjectProvider<ChatModel> provider = mock(ObjectProvider.class);
        MockChatModel mockChatModel = new MockChatModel();
        when(provider.getIfAvailable()).thenReturn(mockChatModel);

        MockVerificationController controller = new MockVerificationController(provider, env);
        ResponseEntity<Map<String, Object>> response = controller.getStatus();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("status")).isEqualTo("SAFE_MOCK");
        assertThat(response.getBody().get("mock")).isEqualTo(true);
        assertThat(response.getBody().get("modelClass")).isEqualTo("MockChatModel");
    }

    @Test
    @DisplayName("Fails closed (404) when real-ai profile is active even if local is also active")
    void returns404WhenRealAiProfileActive() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("local", "real-ai");

        @SuppressWarnings("unchecked")
        ObjectProvider<ChatModel> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(new MockChatModel());

        MockVerificationController controller = new MockVerificationController(provider, env);
        ResponseEntity<Map<String, Object>> response = controller.getStatus();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNull();
    }

    @Test
    @DisplayName("Fails closed (404) when injected ChatModel is not MockChatModel")
    void returns404WhenChatModelIsNotMock() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("local");

        @SuppressWarnings("unchecked")
        ObjectProvider<ChatModel> provider = mock(ObjectProvider.class);
        ChatModel otherChatModel = mock(ChatModel.class);
        when(provider.getIfAvailable()).thenReturn(otherChatModel);

        MockVerificationController controller = new MockVerificationController(provider, env);
        ResponseEntity<Map<String, Object>> response = controller.getStatus();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNull();
    }

    @Test
    @DisplayName("Fails closed (404) when ChatModel is null/unavailable")
    void returns404WhenChatModelIsNull() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("local");

        @SuppressWarnings("unchecked")
        ObjectProvider<ChatModel> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);

        MockVerificationController controller = new MockVerificationController(provider, env);
        ResponseEntity<Map<String, Object>> response = controller.getStatus();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNull();
    }

    @Test
    @DisplayName("Spring profile conditional: Controller bean only loaded when profile includes local")
    void contextRunner_profileConditional() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(MockVerificationController.class)
                .withBean(ObjectProvider.class, () -> mock(ObjectProvider.class));

        runner.withPropertyValues("spring.profiles.active=local")
                .run(context -> assertThat(context).hasSingleBean(MockVerificationController.class));

        runner.withPropertyValues("spring.profiles.active=test")
                .run(context -> assertThat(context).doesNotHaveBean(MockVerificationController.class));

        runner.withPropertyValues("spring.profiles.active=prod")
                .run(context -> assertThat(context).doesNotHaveBean(MockVerificationController.class));
    }
}
