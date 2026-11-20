package io.github.samzhu.bot.execution.internal.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.AsyncHandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.concurrent.CompletableFuture;

/** Only AG-UI waits for MVC to install the emitter before subscribing to the model. */
@Configuration(proxyBeanMethods = false)
class AgUiTransportReadiness implements WebMvcConfigurer {
    static final String ATTRIBUTE = AgUiTransportReadiness.class.getName() + ".ready";

    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AsyncHandlerInterceptor() {
            @Override public void afterConcurrentHandlingStarted(HttpServletRequest request, HttpServletResponse response, Object handler) {
                if (request.getAttribute(ATTRIBUTE) instanceof CompletableFuture<?> ready) ready.complete(null);
            }
        }).addPathPatterns("/api/ag-ui");
    }
}
