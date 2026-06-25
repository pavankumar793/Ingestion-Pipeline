package com.rag.ingestion.service.model;

import java.util.List;

public record GitHubPublishResult(
        boolean enabled,
        String status,
        String repository,
        String branch,
        String pullRequestUrl,
        String reason,
        List<String> publishedFiles
) {

    public static GitHubPublishResult disabled() {
        return new GitHubPublishResult(false, "disabled", null, null, null, null, List.of());
    }
}
