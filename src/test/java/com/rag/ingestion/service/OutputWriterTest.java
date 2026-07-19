package com.rag.ingestion.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.rag.ingestion.config.IngestionProperties;
import com.rag.ingestion.service.model.DocumentChunk;
import com.rag.ingestion.service.model.StoredSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OutputWriterTest {

    @TempDir
    Path outputRoot;

    @Test
    void namesChunksFromHeadingsAndDisambiguatesDuplicates() throws Exception {
        OutputWriter writer = new OutputWriter(properties());

        StoredSource stored = writer.write("batch-1", "guide", "guide.pdf", "abc123", List.of(
                new DocumentChunk("guide-001", 1, "Overview", "# Overview\n\nFirst."),
                new DocumentChunk("guide-002", 2, "Overview", "# Overview\n\nSecond."),
                new DocumentChunk("guide-003", 3, null, "No heading.")
        ));

        assertThat(stored.chunkFiles()).containsExactly(
                "guide/overview.md",
                "guide/overview-002.md",
                "guide/chunk-003.md"
        );
        assertThat(Files.readString(outputRoot.resolve("batch-1").resolve("guide").resolve("overview.md")))
                .contains("chunk_title: \"Overview\"");
    }

    private IngestionProperties properties() {
        return new IngestionProperties(
                outputRoot,
                10,
                26214400,
                new IngestionProperties.Chunk(1000, 120, 80),
                new IngestionProperties.Browser(true, "default", null, outputRoot.resolve("browser-profile"), false, 60, 300),
                new IngestionProperties.Github(false, "pavankumar793/slack-rag-assistant", "main", "docs", "ingestion", "", "https://api.github.com")
        );
    }
}
