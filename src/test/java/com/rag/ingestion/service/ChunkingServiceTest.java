package com.rag.ingestion.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.rag.ingestion.config.IngestionProperties;
import com.rag.ingestion.service.model.DocumentChunk;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChunkingServiceTest {

    @Test
    void keepsDetectedHeadingWithChunkContent() {
        ChunkingService service = new ChunkingService(properties(50, 5));

        List<DocumentChunk> chunks = service.chunk("policy", """
                Account Access

                Users sign in with approved credentials.

                Passwords must be rotated according to policy.
                """);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().heading()).isEqualTo("Account Access");
        assertThat(chunks.getFirst().content()).startsWith("# Account Access");
    }

    @Test
    void splitsLargeContentByParagraphs() {
        ChunkingService service = new ChunkingService(properties(12, 0));

        List<DocumentChunk> chunks = service.chunk("guide", """
                First paragraph has enough words to stand on its own.

                Second paragraph also has enough words to create another chunk.
                """);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).id()).isEqualTo("guide-001");
        assertThat(chunks.get(1).id()).isEqualTo("guide-002");
    }

    @Test
    void keepsNearbyHeadingsWithTheirContentInsteadOfCreatingHeadingOnlyChunks() {
        ChunkingService service = new ChunkingService(properties(1000, 120));

        List<DocumentChunk> chunks = service.chunk("guide", """
                Account Access

                Login Requirements

                Users must sign in with approved credentials.

                Password Reset

                Reset links expire after thirty minutes.

                Document Uploads

                Review Workflow

                Reviewers approve generated chunks before production use.
                """);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().content()).contains("# Account Access");
        assertThat(chunks.getFirst().content()).contains("## Login Requirements");
        assertThat(chunks.getFirst().content()).contains("Users must sign in with approved credentials.");
        assertThat(chunks.getFirst().content()).contains("### Review Workflow");
        assertThat(chunks.getFirst().content()).contains("Reviewers approve generated chunks before production use.");
    }

    @Test
    void infersSimpleHeadingHierarchy() {
        ChunkingService service = new ChunkingService(properties(1000, 120));

        List<DocumentChunk> chunks = service.chunk("guide", """
                Acme Support Knowledge Base

                Sample source document for testing.

                Account Access

                Login Requirements

                Users must sign in with approved credentials.

                Password Reset

                Reset links expire after thirty minutes.

                Document Uploads

                Review Workflow

                Reviewers approve generated chunks before production use.
                """);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().content()).contains("# Acme Support Knowledge Base");
        assertThat(chunks.getFirst().content()).contains("## Account Access");
        assertThat(chunks.getFirst().content()).contains("### Login Requirements");
        assertThat(chunks.getFirst().content()).contains("## Password Reset");
        assertThat(chunks.getFirst().content()).contains("## Document Uploads");
        assertThat(chunks.getFirst().content()).contains("### Review Workflow");
    }

    @Test
    void convertsTableLikeRowsToMarkdownTable() {
        ChunkingService service = new ChunkingService(properties(1000, 120));

        List<DocumentChunk> chunks = service.chunk("guide", """
                Support Ownership Matrix

                Area Primary Owner Review Frequency

                Account access Identity Support Monthly

                Document uploads Content Operations Bi-weekly

                Incident escalation Support Lead Quarterly
                """);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().content()).contains("""
                | Area | Primary Owner | Review Frequency |
                | --- | --- | --- |
                | Account access | Identity Support | Monthly |
                | Document uploads | Content Operations | Bi-weekly |
                | Incident escalation | Support Lead | Quarterly |
                """.trim());
    }

    @Test
    void doesNotConvertOrdinaryParagraphsIntoTables() {
        ChunkingService service = new ChunkingService(properties(1000, 120));

        List<DocumentChunk> chunks = service.chunk("guide", """
                Account Access

                Users must sign in with approved credentials.

                Password Reset

                Reset links expire after thirty minutes.
                """);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().content()).doesNotContain("| --- |");
    }

    private static IngestionProperties properties(int maxWords, int overlapWords) {
        return new IngestionProperties(
                Path.of("docs-unstaged"),
                10,
                26214400,
                new IngestionProperties.Chunk(maxWords, overlapWords, 80),
                new IngestionProperties.Github(false, "pavankumar793/slack-rag-assistant", "main", "docs", "ingestion", "", "https://api.github.com")
        );
    }
}
