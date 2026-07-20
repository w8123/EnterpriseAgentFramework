package com.enterprise.ai.runtime.internalauth;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Size-capped cached request body so HMAC verification and controllers share the same bytes.
 */
public final class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {

    private final byte[] cachedBody;

    public CachedBodyHttpServletRequest(HttpServletRequest request, byte[] cachedBody) {
        super(request);
        this.cachedBody = cachedBody == null ? new byte[0] : cachedBody;
    }

    public byte[] getCachedBody() {
        return cachedBody;
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream input = new ByteArrayInputStream(cachedBody);
        return new ServletInputStream() {
            @Override
            public boolean isFinished() {
                return input.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener readListener) {
                // no-op: body is fully buffered
            }

            @Override
            public int read() {
                return input.read();
            }
        };
    }

    @Override
    public BufferedReader getReader() {
        Charset charset = StandardCharsets.UTF_8;
        String encoding = getCharacterEncoding();
        if (encoding != null && !encoding.isBlank()) {
            charset = Charset.forName(encoding);
        }
        return new BufferedReader(new InputStreamReader(getInputStream(), charset));
    }

    /**
     * Reads at most {@code maxBytes + 1} to detect overflow without unbounded allocation.
     *
     * @throws IOException if body exceeds maxBytes
     */
    public static byte[] readCapped(HttpServletRequest request, int maxBytes) throws IOException {
        if (maxBytes < 1) {
            throw new IOException("max body bytes must be positive");
        }
        try (ServletInputStream in = request.getInputStream()) {
            byte[] buffer = in.readNBytes(maxBytes + 1);
            if (buffer.length > maxBytes) {
                throw new IOException("request body exceeds max-body-bytes");
            }
            return buffer;
        }
    }
}
