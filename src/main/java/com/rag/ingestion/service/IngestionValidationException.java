package com.rag.ingestion.service;

public class IngestionValidationException extends RuntimeException {

    public IngestionValidationException(String message) {
        super(message);
    }
}
