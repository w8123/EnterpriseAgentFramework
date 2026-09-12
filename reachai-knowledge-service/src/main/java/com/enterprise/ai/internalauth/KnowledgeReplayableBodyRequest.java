package com.enterprise.ai.internalauth;

import jakarta.servlet.ReadListener;
import jakarta.servlet.MultipartConfigElement;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.Part;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Collection;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Map;

/**
 * Captures a signed request once, then replays it to Spring MVC. Small bodies
 * stay in memory; larger multipart uploads are spooled to an owner-only temp
 * file and deleted as soon as the request completes.
 */
final class KnowledgeReplayableBodyRequest extends HttpServletRequestWrapper implements AutoCloseable {

    static final int DEFAULT_MEMORY_THRESHOLD_BYTES = 1_048_576;

    private final byte[] memoryBody;
    private final Path bodyFile;
    private final long bodyLength;
    private final String bodySha256;
    private KnowledgeReplayableMultipart multipart;

    /** Called only after authentication; MVC must parse the captured bytes, not the consumed container stream. */
    void configureMultipart(MultipartConfigElement config) {
        if (getContentType() != null && getContentType().toLowerCase(Locale.ROOT).startsWith("multipart/")) {
            multipart = new KnowledgeReplayableMultipart(this, config);
        }
    }

    @Override
    public Collection<Part> getParts() throws IOException, ServletException {
        return multipart == null ? super.getParts() : multipart.parts();
    }

    @Override
    public Part getPart(String name) throws IOException, ServletException {
        if (multipart == null) return super.getPart(name);
        return getParts().stream().filter(part -> part.getName().equals(name)).findFirst().orElse(null);
    }

    @Override
    public Map<String, String[]> getParameterMap() {
        return multipart == null ? super.getParameterMap() : multipart.parameters();
    }

    @Override
    public String[] getParameterValues(String name) {
        return multipart == null ? super.getParameterValues(name) : multipart.values(name);
    }

    @Override
    public String getParameter(String name) {
        if (multipart == null) return super.getParameter(name);
        String[] values = getParameterValues(name);
        return values == null || values.length == 0 ? null : values[0];
    }

    @Override
    public Enumeration<String> getParameterNames() {
        return multipart == null ? super.getParameterNames() : multipart.names();
    }

    private KnowledgeReplayableBodyRequest(HttpServletRequest request,
                                           byte[] memoryBody,
                                           Path bodyFile,
                                           long bodyLength,
                                           String bodySha256) {
        super(request);
        this.memoryBody = memoryBody;
        this.bodyFile = bodyFile;
        this.bodyLength = bodyLength;
        this.bodySha256 = bodySha256;
    }

    static KnowledgeReplayableBodyRequest capture(HttpServletRequest request,
                                                   long maxBodyBytes,
                                                   int memoryThresholdBytes) throws IOException {
        if (maxBodyBytes < 0 || memoryThresholdBytes < 0) {
            throw new IllegalArgumentException("body limits must not be negative");
        }
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }

        ByteArrayOutputStream memory = new ByteArrayOutputStream(
                Math.min(memoryThresholdBytes, 65_536));
        Path temporary = null;
        OutputStream destination = memory;
        long total = 0;
        boolean succeeded = false;
        try (InputStream input = request.getInputStream()) {
            byte[] chunk = new byte[16_384];
            int read;
            while ((read = input.read(chunk)) >= 0) {
                if (read == 0) {
                    continue;
                }
                total += read;
                if (total > maxBodyBytes) {
                    throw new BodyTooLargeException(maxBodyBytes);
                }
                if (temporary == null && total > memoryThresholdBytes) {
                    temporary = Files.createTempFile("reachai-knowledge-signed-body-", ".tmp");
                    OutputStream fileOutput = Files.newOutputStream(temporary);
                    memory.writeTo(fileOutput);
                    destination = fileOutput;
                }
                destination.write(chunk, 0, read);
                digest.update(chunk, 0, read);
            }
            destination.flush();
            succeeded = true;
        } finally {
            if (destination != memory) {
                try {
                    destination.close();
                } catch (IOException ignored) {
                    // Preserve the capture failure; cleanup still runs below.
                }
            }
            if (!succeeded && temporary != null) {
                Files.deleteIfExists(temporary);
            }
        }

        byte[] captured = temporary == null ? memory.toByteArray() : null;
        return new KnowledgeReplayableBodyRequest(
                request,
                captured,
                temporary,
                total,
                HexFormat.of().formatHex(digest.digest()));
    }

    String bodySha256() {
        return bodySha256;
    }

    long bodyLength() {
        return bodyLength;
    }

    boolean spooledToDisk() {
        return bodyFile != null;
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
        InputStream source = bodyFile == null
                ? new ByteArrayInputStream(memoryBody)
                : Files.newInputStream(bodyFile);
        return new ServletInputStream() {
            private boolean finished;

            @Override
            public boolean isFinished() {
                return finished;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener listener) {
                // ReachAI's signed ingress is deliberately synchronous.
            }

            @Override
            public int read() throws IOException {
                int value = source.read();
                if (value < 0) {
                    finished = true;
                }
                return value;
            }

            @Override
            public int read(byte[] target, int offset, int length) throws IOException {
                int value = source.read(target, offset, length);
                if (value < 0) {
                    finished = true;
                }
                return value;
            }

            @Override
            public void close() throws IOException {
                finished = true;
                source.close();
            }
        };
    }

    @Override
    public BufferedReader getReader() throws IOException {
        Charset charset;
        try {
            charset = getCharacterEncoding() == null
                    ? StandardCharsets.UTF_8
                    : Charset.forName(getCharacterEncoding());
        } catch (RuntimeException unsupported) {
            charset = StandardCharsets.UTF_8;
        }
        return new BufferedReader(new InputStreamReader(getInputStream(), charset));
    }

    @Override
    public int getContentLength() {
        return bodyLength > Integer.MAX_VALUE ? -1 : (int) bodyLength;
    }

    @Override
    public long getContentLengthLong() {
        return bodyLength;
    }

    @Override
    public void close() throws IOException {
        try {
            if (multipart != null) multipart.close();
        } finally {
            if (bodyFile != null) Files.deleteIfExists(bodyFile);
        }
    }

    static final class BodyTooLargeException extends IOException {
        BodyTooLargeException(long maxBodyBytes) {
            super("signed request body exceeds " + maxBodyBytes + " bytes");
        }
    }
}
