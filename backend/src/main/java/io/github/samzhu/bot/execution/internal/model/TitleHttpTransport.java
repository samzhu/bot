package io.github.samzhu.bot.execution.internal.model;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.OkHttpClient;

/** Limits each SDK call to one HTTP exchange, including OkHttp follow-ups. */
public final class TitleHttpTransport {
    private static final class ExchangeCount { final AtomicInteger count = new AtomicInteger(); }
    private TitleHttpTransport() {}

    public static OkHttpClient.Builder builder() {
        return new OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
            .addInterceptor(chain -> chain.proceed(chain.request().newBuilder().tag(ExchangeCount.class, new ExchangeCount()).build()))
            .addNetworkInterceptor(chain -> {
                var count = chain.request().tag(ExchangeCount.class);
                if (count == null || count.count.incrementAndGet() != 1) throw new IOException("Title provider exchange cannot be repeated");
                return chain.proceed(chain.request());
            });
    }
}
