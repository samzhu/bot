package io.github.samzhu.bot.identity.internal;

import io.github.samzhu.bot.identity.CurrentUserProvider;
import org.springframework.stereotype.Component;

@Component
public class DevCurrentUserProvider implements CurrentUserProvider {
    public static final String DEV_USER_ID = "dev-user";

    @Override
    public String getCurrentUserId() {
        return DEV_USER_ID;
    }
}
