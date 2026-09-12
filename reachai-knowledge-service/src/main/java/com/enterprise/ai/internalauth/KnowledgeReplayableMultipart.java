package com.enterprise.ai.internalauth;

import jakarta.servlet.MultipartConfigElement;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Part;
import org.apache.catalina.core.ApplicationPart;
import org.apache.tomcat.util.http.fileupload.FileItem;
import org.apache.tomcat.util.http.fileupload.FileUpload;
import org.apache.tomcat.util.http.fileupload.FileUploadException;
import org.apache.tomcat.util.http.fileupload.UploadContext;
import org.apache.tomcat.util.http.fileupload.disk.DiskFileItemFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Tomcat multipart adapter for the already authenticated, replayable request body. */
final class KnowledgeReplayableMultipart implements AutoCloseable {
    private final KnowledgeReplayableBodyRequest request;
    private final MultipartConfigElement config;
    private Path directory;
    private List<FileItem> items = List.of();
    private List<Part> parts;
    private Map<String, String[]> parameters;

    KnowledgeReplayableMultipart(KnowledgeReplayableBodyRequest request, MultipartConfigElement config) {
        this.request = request;
        this.config = config;
    }

    Collection<Part> parts() throws IOException, ServletException {
        parse();
        return parts;
    }

    Map<String, String[]> parameters() {
        Map<String, String[]> copy = new LinkedHashMap<>();
        parsedParameters().forEach((key, values) -> copy.put(key, values.clone()));
        return Collections.unmodifiableMap(copy);
    }

    String[] values(String name) {
        String[] values = parsedParameters().get(name);
        return values == null ? null : values.clone();
    }

    Enumeration<String> names() {
        return Collections.enumeration(parsedParameters().keySet());
    }

    private Map<String, String[]> parsedParameters() {
        try {
            parse();
            return parameters;
        } catch (IOException | ServletException failure) {
            throw new IllegalStateException("Cannot read authenticated multipart fields", failure);
        }
    }

    private void parse() throws IOException, ServletException {
        if (parts != null) return;
        if (config == null) throw new IllegalStateException("Multipart uploads are disabled");
        Path repository = config.getLocation().isBlank()
                ? Path.of(System.getProperty("java.io.tmpdir")) : Path.of(config.getLocation());
        directory = Files.createTempDirectory(repository, "reachai-knowledge-parts-");
        var upload = new FileUpload();
        upload.setFileItemFactory(new DiskFileItemFactory(config.getFileSizeThreshold(), directory.toFile()));
        upload.setSizeMax(config.getMaxRequestSize());
        upload.setFileSizeMax(config.getMaxFileSize());
        // Bound metadata allocation as well as request bytes (Tomcat's default parameter count).
        upload.setFileCountMax(10_000);
        String encoding = request.getCharacterEncoding() == null
                ? StandardCharsets.UTF_8.name() : request.getCharacterEncoding();
        upload.setHeaderEncoding(encoding);
        try {
            try (InputStream source = request.getInputStream()) {
                items = upload.parseRequest(new UploadContext() {
                    @Override public long contentLength() { return request.getContentLengthLong(); }
                    @Override public String getCharacterEncoding() { return encoding; }
                    @Override public String getContentType() { return request.getContentType(); }
                    @Override public InputStream getInputStream() { return source; }
                });
            }
            Map<String, List<String>> values = new LinkedHashMap<>();
            String query = request.getQueryString();
            if (query != null && !query.isEmpty()) {
                for (String pair : query.split("&")) {
                    String[] entry = pair.split("=", 2);
                    values.computeIfAbsent(URLDecoder.decode(entry[0], encoding), ignored -> new ArrayList<>())
                            .add(URLDecoder.decode(entry.length == 2 ? entry[1] : "", encoding));
                }
            }
            List<Part> parsed = new ArrayList<>();
            for (FileItem item : items) {
                parsed.add(new ApplicationPart(item, directory.toFile()));
                if (item.isFormField()) {
                    values.computeIfAbsent(item.getFieldName(), ignored -> new ArrayList<>()).add(item.getString(encoding));
                }
            }
            Map<String, String[]> fields = new LinkedHashMap<>();
            values.forEach((key, value) -> fields.put(key, value.toArray(String[]::new)));
            parameters = fields;
            parts = List.copyOf(parsed);
        } catch (FileUploadException failure) {
            close();
            throw new ServletException("Cannot parse authenticated multipart body", failure);
        } catch (IOException | RuntimeException failure) {
            close();
            throw failure;
        }
    }

    @Override
    public void close() throws IOException {
        try {
            for (FileItem item : items) item.delete();
        } finally {
            if (directory != null) Files.deleteIfExists(directory);
        }
    }
}
