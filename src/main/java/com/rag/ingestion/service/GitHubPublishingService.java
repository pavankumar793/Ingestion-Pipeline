package com.rag.ingestion.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.ingestion.config.IngestionProperties;
import com.rag.ingestion.service.model.BatchResult;
import com.rag.ingestion.service.model.FileResult;
import com.rag.ingestion.service.model.GitHubPublishResult;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class GitHubPublishingService {

    private final IngestionProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public GitHubPublishingService(IngestionProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newHttpClient();
    }

    public GitHubPublishResult publish(BatchResult result) {
        IngestionProperties.Github github = properties.github();
        if (github == null || !github.enabled()) {
            return GitHubPublishResult.disabled();
        }
        if (github.token() == null || github.token().isBlank()) {
            return failure(github, null, "GITHUB_TOKEN is required when GitHub publishing is enabled.");
        }

        String branch = github.branchPrefix() + "/" + result.batchId();
        try {
            createBranch(github, branch);
            List<String> publishedFiles = publishSuccessfulChunks(github, branch, result);
            if (publishedFiles.isEmpty()) {
                return failure(github, branch, "No created or updated Markdown chunks were available to publish.");
            }
            String pullRequestUrl = createPullRequest(github, branch, result, publishedFiles);
            return new GitHubPublishResult(true, "published", github.repository(), branch, pullRequestUrl, null, publishedFiles);
        } catch (Exception exception) {
            return failure(github, branch, exception.getMessage());
        }
    }

    private List<String> publishSuccessfulChunks(IngestionProperties.Github github, String branch, BatchResult result) throws IOException, InterruptedException {
        List<String> published = new ArrayList<>();
        Path batchDirectory = properties.outputRoot().resolve(result.batchId());

        for (FileResult file : result.files()) {
            if (!List.of("created", "updated").contains(file.status())) {
                continue;
            }
            for (String chunkFile : file.chunkFiles()) {
                if (!chunkFile.endsWith(".md")) {
                    continue;
                }
                Path localPath = batchDirectory.resolve(chunkFile);
                String targetPath = normalizePath(github.targetFolder() + "/" + chunkFile);
                String content = Files.readString(localPath, StandardCharsets.UTF_8);
                upsertFile(github, branch, targetPath, content, "Ingest " + file.sourceName() + " docs");
                published.add(targetPath);
            }
        }
        return published;
    }

    private void createBranch(IngestionProperties.Github github, String branch) throws IOException, InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ref", "refs/heads/" + branch);
        body.put("sha", baseCommitSha(github));
        request(github, "POST", "/repos/" + github.repository() + "/git/refs", body);
    }

    private String baseCommitSha(IngestionProperties.Github github) throws IOException, InterruptedException {
        JsonNode ref = request(github, "GET", "/repos/" + github.repository() + "/git/ref/heads/" + encodePath(github.baseBranch()), null);
        return ref.path("object").path("sha").asText();
    }

    private void upsertFile(IngestionProperties.Github github, String branch, String path, String content, String message) throws IOException, InterruptedException {
        String existingSha = existingFileSha(github, branch, path);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", message);
        body.put("content", Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8)));
        body.put("branch", branch);
        if (existingSha != null) {
            body.put("sha", existingSha);
        }
        request(github, "PUT", "/repos/" + github.repository() + "/contents/" + encodePath(path), body);
    }

    private String existingFileSha(IngestionProperties.Github github, String branch, String path) throws IOException, InterruptedException {
        HttpResponse<String> response = rawRequest(github, "GET", "/repos/" + github.repository() + "/contents/" + encodePath(path) + "?ref=" + encodePath(branch), null);
        if (response.statusCode() == 404) {
            return null;
        }
        ensureSuccess(response);
        return objectMapper.readTree(response.body()).path("sha").asText(null);
    }

    private String createPullRequest(IngestionProperties.Github github, String branch, BatchResult result, List<String> publishedFiles) throws IOException, InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", "Ingest docs: " + result.batchId());
        body.put("head", branch);
        body.put("base", github.baseBranch());
        body.put("body", pullRequestBody(result, publishedFiles));
        body.put("draft", false);
        JsonNode pr = request(github, "POST", "/repos/" + github.repository() + "/pulls", body);
        return pr.path("html_url").asText();
    }

    private String pullRequestBody(BatchResult result, List<String> publishedFiles) {
        StringBuilder body = new StringBuilder();
        body.append("## Ingestion Summary\n\n");
        body.append("- Batch: `").append(result.batchId()).append("`\n");
        body.append("- Status: `").append(result.status()).append("`\n\n");

        body.append("### Published Markdown Files\n");
        for (String file : publishedFiles) {
            body.append("- `").append(file).append("`\n");
        }

        body.append("\n### Skipped Files\n");
        appendFilesByStatus(body, result, "skipped");

        body.append("\n### Failed Files\n");
        appendFilesByStatus(body, result, "failed");
        return body.toString();
    }

    private static void appendFilesByStatus(StringBuilder body, BatchResult result, String status) {
        List<FileResult> matches = result.files().stream()
                .filter(file -> status.equals(file.status()))
                .toList();
        if (matches.isEmpty()) {
            body.append("- None\n");
            return;
        }
        for (FileResult file : matches) {
            body.append("- `").append(file.fileName()).append("`");
            if (file.reason() != null && !file.reason().isBlank()) {
                body.append(": ").append(file.reason());
            }
            body.append('\n');
        }
    }

    private JsonNode request(IngestionProperties.Github github, String method, String path, Object body) throws IOException, InterruptedException {
        HttpResponse<String> response = rawRequest(github, method, path, body);
        ensureSuccess(response);
        if (response.body() == null || response.body().isBlank()) {
            return objectMapper.createObjectNode();
        }
        return objectMapper.readTree(response.body());
    }

    private HttpResponse<String> rawRequest(IngestionProperties.Github github, String method, String path, Object body) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(github.apiUrl() + path))
                .header("Accept", "application/vnd.github+json")
                .header("Authorization", "Bearer " + github.token())
                .header("X-GitHub-Api-Version", "2022-11-28");

        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json");
            request.method(method, HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)));
        }
        return httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void ensureSuccess(HttpResponse<String> response) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new GitHubPublishingException("GitHub API returned " + response.statusCode() + ": " + response.body());
        }
    }

    private static String normalizePath(String value) {
        return value.replace("\\", "/").replaceAll("/{2,}", "/").replaceAll("^/", "");
    }

    private static String encodePath(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20").replace("%2F", "/");
    }

    private static GitHubPublishResult failure(IngestionProperties.Github github, String branch, String reason) {
        return new GitHubPublishResult(true, "failed", github.repository(), branch, null, reason, List.of());
    }
}
