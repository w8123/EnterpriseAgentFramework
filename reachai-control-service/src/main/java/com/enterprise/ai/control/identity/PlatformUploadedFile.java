package com.enterprise.ai.control.identity;

import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;

/** A file a platform AI connection uploaded out of band, handed to console handlers that take a multipart file. */
public final class PlatformUploadedFile implements MultipartFile {
    private final String fileName;
    private final byte[] content;

    public PlatformUploadedFile(String fileName, byte[] content) {
        this.fileName = fileName;
        this.content = content == null ? new byte[0] : content;
    }

    @Override public String getName() { return "file"; }
    @Override public String getOriginalFilename() { return fileName; }
    @Override public String getContentType() { return "application/octet-stream"; }
    @Override public boolean isEmpty() { return content.length == 0; }
    @Override public long getSize() { return content.length; }
    @Override public byte[] getBytes() { return content.clone(); }
    @Override public InputStream getInputStream() { return new ByteArrayInputStream(content); }
    @Override public void transferTo(File dest) throws IOException { Files.write(dest.toPath(), content); }
}
