package com.rag.ingestion.service.model;

public record DocumentChunk(
        String id,
        int index,
        String heading,
        String content
) {
}
