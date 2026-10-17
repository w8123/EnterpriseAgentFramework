package com.enterprise.ai.control.config.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;

/**
 * Drops hop-by-hop headers that controllers copy from downstream Feign responses.
 * Tomcat frames the response itself, so a forwarded {@code Transfer-Encoding} is emitted twice;
 * Vite tolerates that, but Nginx rejects the upstream response with 502.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class HopByHopResponseHeaderFilter extends OncePerRequestFilter {

    // Connection/Upgrade stay untouched so servlet-level protocol upgrades keep working.
    static final Set<String> HOP_BY_HOP_HEADERS = Set.of(
            "keep-alive",
            "proxy-authenticate",
            "proxy-authorization",
            "te",
            "trailer",
            "transfer-encoding");

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        filterChain.doFilter(request, new HopByHopFilteringResponse(response));
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    static boolean isHopByHop(String name) {
        return name != null && HOP_BY_HOP_HEADERS.contains(name.toLowerCase(Locale.ROOT));
    }

    static final class HopByHopFilteringResponse extends HttpServletResponseWrapper {

        HopByHopFilteringResponse(HttpServletResponse response) {
            super(response);
        }

        @Override
        public void setHeader(String name, String value) {
            if (!isHopByHop(name)) {
                super.setHeader(name, value);
            }
        }

        @Override
        public void addHeader(String name, String value) {
            if (!isHopByHop(name)) {
                super.addHeader(name, value);
            }
        }
    }
}
