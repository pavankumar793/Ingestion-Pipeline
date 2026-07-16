package com.rag.ingestion.service.model;

public record UrlFetchOptions(
        String bearerToken,
        String cookieHeader
) {

    public static UrlFetchOptions none() {
        return new UrlFetchOptions(null, null);
    }
}
