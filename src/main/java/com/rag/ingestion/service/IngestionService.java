package com.rag.ingestion.service;

import com.rag.ingestion.config.IngestionProperties;
import com.rag.ingestion.service.model.BatchResult;
import com.rag.ingestion.service.model.DocumentChunk;
import com.rag.ingestion.service.model.ExtractedDocument;
import com.rag.ingestion.service.model.FileResult;
import com.rag.ingestion.service.model.GitHubPublishResult;
import com.rag.ingestion.service.model.StoredSource;
import com.rag.ingestion.service.model.UrlDocument;
import com.rag.ingestion.service.model.UrlFetchOptions;
import java.io.IOException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class IngestionService {

    private static final DateTimeFormatter BATCH_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    private final IngestionProperties properties;
    private final TextExtractionService extractionService;
    private final UrlContentService urlContentService;
    private final ChunkingService chunkingService;
    private final OutputWriter outputWriter;
    private final ManifestWriter manifestWriter;
    private final SourceStateRepository sourceStateRepository;
    private final GitHubPublishingService gitHubPublishingService;
    private final Clock clock;

    public IngestionService(
            IngestionProperties properties,
            TextExtractionService extractionService,
            UrlContentService urlContentService,
            ChunkingService chunkingService,
            OutputWriter outputWriter,
            ManifestWriter manifestWriter,
            SourceStateRepository sourceStateRepository,
            GitHubPublishingService gitHubPublishingService,
            Clock clock
    ) {
        this.properties = properties;
        this.extractionService = extractionService;
        this.urlContentService = urlContentService;
        this.chunkingService = chunkingService;
        this.outputWriter = outputWriter;
        this.manifestWriter = manifestWriter;
        this.sourceStateRepository = sourceStateRepository;
        this.gitHubPublishingService = gitHubPublishingService;
        this.clock = clock;
    }

    public BatchResult ingest(List<MultipartFile> files) throws IOException {
        validateRequest(files);

        String batchId = "batch-" + LocalDateTime.now(clock).format(BATCH_FORMAT);
        List<FileResult> results = new ArrayList<>();

        for (MultipartFile file : files) {
            results.add(processFile(batchId, file));
        }

        BatchResult result = new BatchResult(batchId, batchStatus(results), properties.outputRoot().resolve(batchId).toString(), results, GitHubPublishResult.disabled());
        return finishBatch(result);
    }

    public BatchResult ingestUrls(List<String> urls) throws IOException {
        return ingestUrls(urls, null, null);
    }

    public BatchResult ingestUrls(List<String> urls, String bearerToken) throws IOException {
        return ingestUrls(urls, bearerToken, null);
    }

    public BatchResult ingestUrls(List<String> urls, String bearerToken, String cookieHeader) throws IOException {
        validateUrls(urls);

        String batchId = "batch-" + LocalDateTime.now(clock).format(BATCH_FORMAT);
        List<FileResult> results = new ArrayList<>();
        UrlFetchOptions options = new UrlFetchOptions(bearerToken, cookieHeader);

        for (String url : urls) {
            results.add(processUrl(batchId, url, options));
        }

        BatchResult result = new BatchResult(batchId, batchStatus(results), properties.outputRoot().resolve(batchId).toString(), results, GitHubPublishResult.disabled());
        return finishBatch(result);
    }

    private BatchResult finishBatch(BatchResult result) throws IOException {
        manifestWriter.write(result);
        GitHubPublishResult gitHub = gitHubPublishingService.publish(result);
        BatchResult resultWithPublish = new BatchResult(result.batchId(), result.status(), result.outputPath(), result.files(), gitHub);
        manifestWriter.write(resultWithPublish);
        return resultWithPublish;
    }

    private FileResult processFile(String batchId, MultipartFile file) {
        String fileName = cleanFileName(file.getOriginalFilename());
        String sourceName = Slugifier.slugify(stripExtension(fileName));

        try {
            String hash = Hashing.sha256Hex(file.getBytes());

            if (sourceStateRepository.hasSameHash(sourceName, hash)) {
                return new FileResult(fileName, sourceName, "skipped", "Content hash unchanged.", hash, 0, List.of());
            }
            boolean existingSource = sourceStateRepository.exists(sourceName);

            ExtractedDocument document = extractionService.extract(fileName, file.getContentType(), file.getInputStream());
            if (document.text().length() < properties.chunk().minExtractedCharacters()) {
                return new FileResult(fileName, sourceName, "failed", "No reliable readable text extracted. Re-upload a text-based PDF, DOCX, or TXT file.", hash, 0, List.of());
            }

            List<DocumentChunk> chunks = chunkingService.chunk(sourceName, document.text());
            StoredSource stored = outputWriter.write(batchId, sourceName, fileName, hash, chunks);
            sourceStateRepository.save(sourceName, hash);
            String status = existingSource ? "updated" : "created";
            return new FileResult(fileName, sourceName, status, null, hash, chunks.size(), stored.chunkFiles());
        } catch (Exception exception) {
            return new FileResult(fileName, sourceName, "failed", exception.getMessage(), null, 0, List.of());
        }
    }

    private FileResult processUrl(String batchId, String url, UrlFetchOptions options) {
        try {
            UrlDocument document = urlContentService.fetch(url, options);
            if (sourceStateRepository.hasSameHash(document.sourceName(), document.contentHash())) {
                return new FileResult(url, document.sourceName(), "skipped", "Content hash unchanged.", document.contentHash(), 0, List.of());
            }
            boolean existingSource = sourceStateRepository.exists(document.sourceName());

            if (document.text().length() < properties.chunk().minExtractedCharacters()) {
                return new FileResult(url, document.sourceName(), "failed", "No reliable readable text extracted from URL.", document.contentHash(), 0, List.of());
            }

            List<DocumentChunk> chunks = chunkingService.chunk(document.sourceName(), document.text());
            StoredSource stored = outputWriter.write(batchId, document.sourceName(), url, document.contentHash(), chunks);
            sourceStateRepository.save(document.sourceName(), document.contentHash());
            String status = existingSource ? "updated" : "created";
            return new FileResult(url, document.sourceName(), status, null, document.contentHash(), chunks.size(), stored.chunkFiles());
        } catch (Exception exception) {
            String sourceName = Slugifier.slugify(url == null || url.isBlank() ? "web-page" : url);
            return new FileResult(url, sourceName, "failed", exception.getMessage(), null, 0, List.of());
        }
    }

    private void validateRequest(List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            throw new IngestionValidationException("Upload at least one file.");
        }
        if (files.size() > properties.maxFilesPerRequest()) {
            throw new IngestionValidationException("Upload at most " + properties.maxFilesPerRequest() + " files.");
        }
        Set<String> sourceNames = new HashSet<>();
        for (MultipartFile file : files) {
            String name = cleanFileName(file.getOriginalFilename());
            if (file.isEmpty()) {
                throw new IngestionValidationException(name + " is empty.");
            }
            if (file.getSize() > properties.maxFileSizeBytes()) {
                throw new IngestionValidationException(name + " exceeds the 25MB per-file limit.");
            }
            String extension = extension(name);
            if (!List.of("txt", "docx", "pdf").contains(extension)) {
                throw new IngestionValidationException(name + " is not supported. Use .txt, .docx, or .pdf.");
            }
            String sourceName = Slugifier.slugify(stripExtension(name));
            if (!sourceNames.add(sourceName)) {
                throw new IngestionValidationException("Multiple files resolve to source name `" + sourceName + "`. Rename one file and retry.");
            }
        }
    }

    private void validateUrls(List<String> urls) {
        if (urls == null || urls.isEmpty()) {
            throw new IngestionValidationException("Provide at least one URL.");
        }
        if (urls.size() > properties.maxFilesPerRequest()) {
            throw new IngestionValidationException("Provide at most " + properties.maxFilesPerRequest() + " URLs.");
        }
        Set<String> uniqueUrls = new HashSet<>();
        for (String url : urls) {
            if (url == null || url.isBlank()) {
                throw new IngestionValidationException("URL cannot be blank.");
            }
            if (!uniqueUrls.add(url.trim())) {
                throw new IngestionValidationException("Duplicate URL: " + url);
            }
        }
    }

    private static String batchStatus(List<FileResult> results) {
        long failed = results.stream().filter(result -> "failed".equals(result.status())).count();
        if (failed == results.size()) {
            return "failed";
        }
        if (failed > 0) {
            return "partial_failed";
        }
        return "success";
    }

    private static String cleanFileName(String name) {
        if (name == null || name.isBlank()) {
            return "uploaded-file";
        }
        return name.replace("\\", "/").substring(name.replace("\\", "/").lastIndexOf('/') + 1);
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private static String extension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(dot + 1).toLowerCase() : "";
    }
}
