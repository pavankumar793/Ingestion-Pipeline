package com.rag.ingestion.service;

import com.rag.ingestion.config.IngestionProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.stereotype.Repository;

@Repository
public class SourceStateRepository {

    private final IngestionProperties properties;

    public SourceStateRepository(IngestionProperties properties) {
        this.properties = properties;
    }

    public boolean hasSameHash(String sourceName, String hash) throws IOException {
        Path hashFile = hashFile(sourceName);
        return Files.exists(hashFile) && Files.readString(hashFile, StandardCharsets.UTF_8).trim().equals(hash);
    }

    public boolean exists(String sourceName) {
        return Files.exists(hashFile(sourceName));
    }

    public void save(String sourceName, String hash) throws IOException {
        Path hashFile = hashFile(sourceName);
        Files.createDirectories(hashFile.getParent());
        Files.writeString(hashFile, hash + "\n", StandardCharsets.UTF_8);
    }

    private Path hashFile(String sourceName) {
        return properties.outputRoot().resolve(".state").resolve(sourceName + ".sha256");
    }
}
