package com.rag.ingestion.service.model;

public record UrlFetchOptions(
        String bearerToken
) {

    public static UrlFetchOptions none() {
        return new UrlFetchOptions(null);
    }
}
