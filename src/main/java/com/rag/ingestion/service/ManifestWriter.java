package com.rag.ingestion.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.rag.ingestion.config.IngestionProperties;
import com.rag.ingestion.service.model.BatchResult;
import com.rag.ingestion.service.model.FileResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.stereotype.Service;

@Service
public class ManifestWriter {

    private final IngestionProperties properties;
    private final ObjectMapper objectMapper;

    public ManifestWriter(IngestionProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper.copy().enable(SerializationFeature.INDENT_OUTPUT);
    }

    public void write(BatchResult result) throws IOException {
        Path batchDirectory = properties.outputRoot().resolve(result.batchId());
        Files.createDirectories(batchDirectory);
        objectMapper.writeValue(batchDirectory.resolve("manifest.json").toFile(), result);
        Files.writeString(batchDirectory.resolve("manifest.md"), markdown(result), StandardCharsets.UTF_8);
    }

    private static String markdown(BatchResult result) {
        StringBuilder builder = new StringBuilder();
        builder.append("# Ingestion Manifest\n\n");
        builder.append("- Batch ID: `").append(result.batchId()).append("`\n");
        builder.append("- Status: `").append(result.status()).append("`\n");
        builder.append("- Output: `").append(result.outputPath()).append("`\n\n");
        builder.append("| File | Source | Status | Chunks | Reason |\n");
        builder.append("| --- | --- | --- | ---: | --- |\n");
        for (FileResult file : result.files()) {
            builder.append("| ")
                    .append(escape(file.fileName()))
                    .append(" | `")
                    .append(file.sourceName())
                    .append("` | `")
                    .append(file.status())
                    .append("` | ")
                    .append(file.chunkCount())
                    .append(" | ")
                    .append(escape(file.reason() == null ? "" : file.reason()))
                    .append(" |\n");
        }
        return builder.toString();
    }

    private static String escape(String value) {
        return value.replace("|", "\\|").replace("\n", " ");
    }
}
