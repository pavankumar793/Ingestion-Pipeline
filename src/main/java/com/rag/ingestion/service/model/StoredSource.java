package com.rag.ingestion.service.model;

import java.nio.file.Path;
import java.util.List;

public record StoredSource(
        Path sourceDirectory,
        List<String> chunkFiles
) {
}
