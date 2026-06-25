package com.rag.ingestion.service.model;

import java.util.List;

public record FileResult(
        String fileName,
        String sourceName,
        String status,
        String reason,
        String sourceHash,
        int chunkCount,
        List<String> chunkFiles
) {
}
