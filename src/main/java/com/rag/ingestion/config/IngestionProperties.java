package com.rag.ingestion.config;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ingestion")
public record IngestionProperties(
        Path outputRoot,
        int maxFilesPerRequest,
        long maxFileSizeBytes,
        Chunk chunk,
        Browser browser,
        Github github
) {
    public record Chunk(
            int maxWords,
            int overlapWords,
            int minExtractedCharacters
    ) {
    }

    public record Github(
            boolean enabled,
            String repository,
            String baseBranch,
            String targetFolder,
            String branchPrefix,
            String token,
            String apiUrl
    ) {
    }

    public record Browser(
            boolean enabled,
            String provider,
            String executablePath,
            Path profilePath,
            boolean headless,
            int navigationTimeoutSeconds,
            int interactiveTimeoutSeconds
    ) {
    }
}
