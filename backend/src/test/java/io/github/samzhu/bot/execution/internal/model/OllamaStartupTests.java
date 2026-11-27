package io.github.samzhu.bot.execution.internal.model;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class OllamaStartupTests {
    @Configuration(proxyBeanMethods = false)
    @ComponentScan(basePackageClasses = GoogleGenAiManualConfig.class)
    static class ModelConfiguration { }

    @Test void explicitOllamaCreatesOnlyLocalChatModelEvenWithGeminiKey() {
        new ApplicationContextRunner().withUserConfiguration(ModelConfiguration.class)
            .withPropertyValues("spring.profiles.active=local", "spring.ai.model.chat=ollama",
                "spring.ai.ollama.chat.model=fixture-model", "bot-google-api-key=synthetic-key-only")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(ChatModel.class);
                assertThat(context.getBean(ChatModel.class).getClass().getName())
                    .isEqualTo("org.springframework.ai.ollama.OllamaChatModel");
            });
    }
    @Test void acceptsOllamaOnAnotherHost() {
        new ApplicationContextRunner().withUserConfiguration(ModelConfiguration.class)
            .withPropertyValues("spring.profiles.active=local", "spring.ai.model.chat=ollama",
                "spring.ai.ollama.chat.model=fixture-model", "spring.ai.ollama.base-url=http://ollama.example.invalid:11434")
            .run(context -> assertThat(context).hasNotFailed().hasSingleBean(ChatModel.class));
    }
    @Test void rejectsOllamaEndpointWithAPath() {
        new ApplicationContextRunner().withUserConfiguration(ModelConfiguration.class)
            .withPropertyValues("spring.profiles.active=local", "spring.ai.model.chat=ollama",
                "spring.ai.ollama.chat.model=fixture-model", "spring.ai.ollama.base-url=http://ollama.example.invalid/api")
            .run(context -> assertThat(context).hasFailed());
    }
    @Test void localChatUsesOnlyLoopbackFixtureAndNeverPullsModel() throws Exception {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var pulls = new java.util.concurrent.atomic.AtomicInteger();
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", exchange -> {
            calls.incrementAndGet();
            byte[] body = "{\"model\":\"fixture-model\",\"message\":{\"role\":\"assistant\",\"content\":\"fixture reply\"},\"done\":true,\"prompt_eval_count\":2,\"eval_count\":3}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.createContext("/api/pull", exchange -> { pulls.incrementAndGet(); exchange.sendResponseHeaders(500, -1); exchange.close(); });
        server.start();
        try {
            new ApplicationContextRunner().withUserConfiguration(ModelConfiguration.class)
                .withPropertyValues("spring.profiles.active=local", "spring.ai.model.chat=ollama",
                    "spring.ai.ollama.chat.model=fixture-model", "bot-google-api-key=synthetic-key-only",
                    "spring.ai.ollama.base-url=http://127.0.0.1:" + server.getAddress().getPort())
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(ChatModel.class);
                    assertThat(context.getBean(ChatModel.class).call("fixture prompt")).isEqualTo("fixture reply");
                    assertThat(context.getBeansOfType(io.github.samzhu.bot.conversation.ConversationTitleGenerator.class)).isEmpty();
                });
            assertThat(calls.get()).isEqualTo(1);
            assertThat(pulls.get()).isZero();
        } finally { server.stop(0); }
    }
    @Test void ollamaRedirectDoesNotReachAnotherOrigin() throws Exception {
        var reached = new java.util.concurrent.atomic.AtomicInteger();
        var target = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        target.createContext("/", exchange -> { reached.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close(); });
        target.start();
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", exchange -> {
            exchange.getResponseHeaders().set("Location", "http://127.0.0.1:" + target.getAddress().getPort() + "/");
            exchange.sendResponseHeaders(302, -1); exchange.close();
        });
        server.start();
        try {
            new ApplicationContextRunner().withUserConfiguration(ModelConfiguration.class)
                .withPropertyValues("spring.profiles.active=local", "spring.ai.model.chat=ollama", "spring.ai.ollama.chat.model=fixture-model",
                    "spring.ai.ollama.base-url=http://127.0.0.1:" + server.getAddress().getPort())
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    org.assertj.core.api.Assertions.assertThatThrownBy(() -> context.getBean(ChatModel.class).call("fixture prompt"));
                });
            assertThat(reached.get()).isZero();
        } finally { server.stop(0); target.stop(0); }
    }
}
