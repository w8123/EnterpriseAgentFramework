package com.enterprise.ai.pipeline.document;

import java.io.IOException;
import java.io.InputStream;

/** Opens a fresh stream every time the provider needs to inspect or parse a document. */
@FunctionalInterface
public interface DocumentContent {

    InputStream openStream() throws IOException;
}
