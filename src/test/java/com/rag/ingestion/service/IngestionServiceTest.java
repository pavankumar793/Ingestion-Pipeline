package com.rag.ingestion.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.ingestion.config.IngestionProperties;
import com.rag.ingestion.service.model.BatchResult;
import com.rag.ingestion.service.model.UrlDocument;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

class IngestionServiceTest {

    @TempDir
    Path outputRoot;

    @Test
    void writesChunksAndManifestsForUploadedTextFile() throws Exception {
        IngestionService service = service();
        MockMultipartFile file = new MockMultipartFile(
                "files",
                "Employee Handbook.txt",
                "text/plain",
                """
                        AUTHENTICATION

                        Users sign in with approved credentials and follow the account policy.

                        Password reset requests are reviewed before access is restored.
                        """.getBytes()
        );

        BatchResult result = service.ingest(List.of(file));

        assertThat(result.status()).isEqualTo("success");
        assertThat(result.files()).singleElement().satisfies(fileResult -> {
            assertThat(fileResult.status()).isEqualTo("created");
            assertThat(fileResult.sourceName()).isEqualTo("employee-handbook");
            assertThat(fileResult.chunkCount()).isEqualTo(1);
        });
        assertThat(result.github().status()).isEqualTo("disabled");

        Path batchDirectory = outputRoot.resolve(result.batchId());
        assertThat(batchDirectory.resolve("manifest.json")).exists();
        assertThat(batchDirectory.resolve("manifest.md")).exists();
        String chunk = Files.readString(batchDirectory.resolve("employee-handbook").resolve("authentication.md"));
        assertThat(chunk).contains("source_file: \"Employee Handbook.txt\"");
        assertThat(chunk).contains("chunk_title: \"AUTHENTICATION\"");
        assertThat(chunk).contains("# AUTHENTICATION");
    }

    @Test
    void skipsUnchangedSourceByHash() throws Exception {
        IngestionService service = service();
        MockMultipartFile file = new MockMultipartFile("files", "Policy.txt", "text/plain", "POLICY\n\nReadable policy content for ingestion.".getBytes());

        service.ingest(List.of(file));
        BatchResult secondResult = service.ingest(List.of(file));

        assertThat(secondResult.files()).singleElement().satisfies(fileResult -> {
            assertThat(fileResult.status()).isEqualTo("skipped");
            assertThat(fileResult.reason()).isEqualTo("Content hash unchanged.");
        });
    }

    @Test
    void writesChunksAndManifestsForUrlInput() throws Exception {
        IngestionService service = service(new StubUrlContentService());

        BatchResult result = service.ingestUrls(List.of("https://example.com/wiki/support"));

        assertThat(result.status()).isEqualTo("success");
        assertThat(result.files()).singleElement().satisfies(fileResult -> {
            assertThat(fileResult.fileName()).isEqualTo("https://example.com/wiki/support");
            assertThat(fileResult.sourceName()).isEqualTo("support-wiki");
            assertThat(fileResult.status()).isEqualTo("created");
        });
        Path chunkPath = outputRoot.resolve(result.batchId()).resolve("support-wiki").resolve("support-wiki.md");
        assertThat(Files.readString(chunkPath)).contains("# Support Wiki");
        assertThat(Files.readString(chunkPath)).contains("Support page content for ingestion.");
    }

    private IngestionService service() {
        return service(new UrlContentService());
    }

    private IngestionService service(UrlContentService urlContentService) {
        ObjectMapper objectMapper = new ObjectMapper();
        IngestionProperties properties = properties(1000, 120, 20);
        return new IngestionService(
                properties,
                new TextExtractionService(),
                urlContentService,
                new ChunkingService(properties),
                new OutputWriter(properties),
                new ManifestWriter(properties, objectMapper),
                new SourceStateRepository(properties),
                new GitHubPublishingService(properties, objectMapper),
                Clock.fixed(Instant.parse("2026-06-21T12:00:00Z"), ZoneOffset.UTC)
        );
    }

    private IngestionProperties properties(int maxWords, int overlapWords, int minExtractedCharacters) {
        return new IngestionProperties(
                outputRoot,
                10,
                26214400,
                new IngestionProperties.Chunk(maxWords, overlapWords, minExtractedCharacters),
                new IngestionProperties.Github(false, "pavankumar793/slack-rag-assistant", "main", "docs", "ingestion", "", "https://api.github.com")
        );
    }

    private static class StubUrlContentService extends UrlContentService {

        @Override
        public UrlDocument fetch(String url) {
            return new UrlDocument(
                    url,
                    "support-wiki",
                    """
                            Support Wiki

                            Support page content for ingestion.
                            """,
                    Hashing.sha256Hex("Support page content for ingestion.".getBytes())
            );
        }
    }
}
