package com.rag.ingestion.api;

import com.rag.ingestion.service.IngestionService;
import com.rag.ingestion.service.model.BatchResult;
import jakarta.validation.constraints.NotEmpty;
import java.io.IOException;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/ingestions")
public class IngestionController {

    private final IngestionService ingestionService;

    public IngestionController(IngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public BatchResult create(@RequestPart("files") @NotEmpty List<MultipartFile> files) throws IOException {
        return ingestionService.ingest(files);
    }
}
