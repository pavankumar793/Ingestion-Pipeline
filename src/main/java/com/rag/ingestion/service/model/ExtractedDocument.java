package com.rag.ingestion.service.model;

public record ExtractedDocument(
        String fileName,
        String contentType,
        String text
) {
}
