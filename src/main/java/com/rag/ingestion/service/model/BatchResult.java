package com.rag.ingestion.service.model;

import java.util.List;

public record BatchResult(
        String batchId,
        String status,
        String outputPath,
        List<FileResult> files,
        GitHubPublishResult github
) {
}
