package com.enterprise.ai.internalauth;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

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
        if (bodyFile != null) {
            Files.deleteIfExists(bodyFile);
        }
    }

    static final class BodyTooLargeException extends IOException {
        BodyTooLargeException(long maxBodyBytes) {
            super("signed request body exceeds " + maxBodyBytes + " bytes");
        }
    }
}
