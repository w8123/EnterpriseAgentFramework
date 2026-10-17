package com.enterprise.ai.control.config.web;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class HopByHopResponseHeaderFilterTest {

    @Test
    void dropsForwardedTransferEncodingButKeepsEndToEndHeaders() throws Exception {
        HttpHeaders downstream = new HttpHeaders();
        downstream.add("transfer-encoding", "chunked");
        downstream.add("Keep-Alive", "timeout=60");
        downstream.add(HttpHeaders.CONTENT_TYPE, "application/json");
        downstream.add("X-Trace-Id", "t-1");
        ResponseEntity<String> proxied = ResponseEntity.status(HttpStatus.OK).headers(downstream).body("{}");

        MockHttpServletResponse response = new MockHttpServletResponse();
        new HopByHopResponseHeaderFilter().doFilter(new MockHttpServletRequest(), response,
                (req, res) -> {
                    ServletServerHttpResponse out = new ServletServerHttpResponse(
                            (jakarta.servlet.http.HttpServletResponse) res);
                    out.getHeaders().putAll(proxied.getHeaders());
                    out.flush();
                });

        assertThat(response.getHeaderNames())
                .doesNotContain("transfer-encoding", "Transfer-Encoding", "Keep-Alive");
        assertThat(response.getHeader(HttpHeaders.CONTENT_TYPE)).startsWith("application/json");
        assertThat(response.getHeader("X-Trace-Id")).isEqualTo("t-1");
    }
}
