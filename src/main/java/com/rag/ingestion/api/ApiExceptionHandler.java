package com.rag.ingestion.api;

import com.rag.ingestion.service.IngestionValidationException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(IngestionValidationException.class)
    public ProblemDetail validation(IngestionValidationException exception) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
        detail.setTitle("Invalid ingestion request");
        return detail;
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ProblemDetail uploadTooLarge(MaxUploadSizeExceededException exception) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.PAYLOAD_TOO_LARGE, "One or more files exceed the upload size limit.");
        detail.setTitle("Upload too large");
        return detail;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail generic(Exception exception) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Ingestion failed unexpectedly.");
        detail.setTitle("Internal error");
        detail.setProperty("error", Map.of("type", exception.getClass().getSimpleName()));
        return detail;
    }
}
