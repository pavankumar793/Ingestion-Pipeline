package com.rag.ingestion.api;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record UrlIngestionRequest(
        @NotEmpty List<String> urls,
        String bearerToken
) {
}
