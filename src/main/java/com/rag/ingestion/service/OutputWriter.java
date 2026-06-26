package com.rag.ingestion.service;

import com.rag.ingestion.config.IngestionProperties;
import com.rag.ingestion.service.model.DocumentChunk;
import com.rag.ingestion.service.model.StoredSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class OutputWriter {

    private final IngestionProperties properties;

    public OutputWriter(IngestionProperties properties) {
        this.properties = properties;
    }

    public StoredSource write(String batchId, String sourceName, String fileName, String sourceHash, List<DocumentChunk> chunks) throws IOException {
        Path sourceDirectory = properties.outputRoot().resolve(batchId).resolve(sourceName);
        recreateDirectory(sourceDirectory);

        List<String> chunkFiles = new ArrayList<>();
        Map<String, Integer> fileNameCounts = new HashMap<>();
        for (DocumentChunk chunk : chunks) {
            String chunkFileName = chunkFileName(chunk, fileNameCounts);
            Files.writeString(sourceDirectory.resolve(chunkFileName), chunkMarkdown(fileName, sourceName, sourceHash, chunk), StandardCharsets.UTF_8);
            chunkFiles.add(sourceName + "/" + chunkFileName);
        }

        return new StoredSource(sourceDirectory, chunkFiles);
    }

    private static String chunkMarkdown(String fileName, String sourceName, String sourceHash, DocumentChunk chunk) {
        return """
                ---
                source_file: "%s"
                source_name: "%s"
                source_hash: "%s"
                chunk_id: "%s"
                chunk_index: %d
                chunk_title: "%s"
                status: "unstaged"
                ---

                %s
                """.formatted(escapeYaml(fileName), sourceName, sourceHash, chunk.id(), chunk.index(), escapeYaml(chunkTitle(chunk)), chunk.content()).stripTrailing() + "\n";
    }

    private static String chunkFileName(DocumentChunk chunk, Map<String, Integer> fileNameCounts) {
        String base = Slugifier.slugify(chunkTitle(chunk));
        if ("source".equals(base)) {
            base = "chunk-" + String.format("%03d", chunk.index());
        }

        int count = fileNameCounts.merge(base, 1, Integer::sum);
        if (count == 1) {
            return base + ".md";
        }
        return base + "-" + String.format("%03d", chunk.index()) + ".md";
    }

    private static String chunkTitle(DocumentChunk chunk) {
        if (chunk.heading() != null && !chunk.heading().isBlank()) {
            return chunk.heading();
        }
        return "chunk-" + String.format("%03d", chunk.index());
    }

    private static String escapeYaml(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void recreateDirectory(Path directory) throws IOException {
        if (Files.exists(directory)) {
            try (var paths = Files.walk(directory)) {
                paths.sorted(Comparator.reverseOrder())
                        .filter(path -> !path.equals(directory))
                        .forEach(OutputWriter::deleteQuietly);
            }
        }
        Files.createDirectories(directory);
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not delete " + path, exception);
        }
    }
}
