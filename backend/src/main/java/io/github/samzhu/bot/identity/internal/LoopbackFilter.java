package io.github.samzhu.bot.identity.internal;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.InetAddress;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LoopbackFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String remoteAddr = request.getRemoteAddr();

        if (remoteAddr != null) {
            try {
                InetAddress inet = InetAddress.getByName(remoteAddr);
                if (!inet.isLoopbackAddress()) {
                    response.sendError(HttpServletResponse.SC_FORBIDDEN, "Access restricted to loopback addresses only.");
                    return;
                }
            } catch (Exception e) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN, "Invalid remote address.");
                return;
            }
        }

        filterChain.doFilter(request, response);
    }
}
