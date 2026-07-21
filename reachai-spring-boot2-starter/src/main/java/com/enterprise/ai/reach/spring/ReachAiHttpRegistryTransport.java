package com.enterprise.ai.reach.spring;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public class ReachAiHttpRegistryTransport implements ReachAiRegistryTransport {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private int connectTimeoutMs = 5000;
    private int readTimeoutMs = 60000;

    @Override
    public String exchange(String method, String url, Map<String, String> headers, Object body) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(connectTimeoutMs);
        connection.setReadTimeout(readTimeoutMs);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Content-Type", "application/json;charset=UTF-8");
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (entry.getValue() != null) {
                    connection.setRequestProperty(entry.getKey(), entry.getValue());
                }
            }
        }
        if (body != null) {
            connection.setDoOutput(true);
            byte[] bytes = objectMapper.writeValueAsBytes(body);
            OutputStream output = connection.getOutputStream();
            try {
                output.write(bytes);
            } finally {
                output.close();
            }
        }
        int status = connection.getResponseCode();
        String response = read(status >= 400 ? connection.getErrorStream() : connection.getInputStream());
        if (status >= 400) {
            throw new IOException("ReachAI registry request failed: status=" + status + " body=" + response);
        }
        return response;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public void setReadTimeoutMs(int readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
    }

    private String read(InputStream input) throws IOException {
        if (input == null) {
            return "";
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int n;
        try {
            while ((n = input.read(chunk)) >= 0) {
                buffer.write(chunk, 0, n);
            }
        } finally {
            input.close();
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }
}
