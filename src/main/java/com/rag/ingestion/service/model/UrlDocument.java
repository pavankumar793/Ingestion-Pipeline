package com.rag.ingestion.service.model;

public record UrlDocument(
        String url,
        String sourceName,
        String text,
        String contentHash
) {
}
