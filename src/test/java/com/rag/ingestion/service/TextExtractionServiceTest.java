package com.rag.ingestion.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class TextExtractionServiceTest {

    private final TextExtractionService service = new TextExtractionService();

    @Test
    void unwrapsPdfStyleLineBreaksInsideParagraphs() throws Exception {
        String text = """
                Acme Support Knowledge Base
                Sample source document for testing

                Account Access

                Users must sign in with their company email address and a password that meets the current complexity
                policy. Accounts are locked after five failed login attempts within fifteen minutes. Locked accounts can be
                restored by the support team after identity verification.

                Severity three incidents are minor defects with available

                workarounds.

                - First list item
                - Second list item
                """;

        String extracted = service.extract("sample.txt", "text/plain", new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8))).text();

        assertThat(extracted).contains("Acme Support Knowledge Base\n\nSample source document for testing\n\nAccount Access");
        assertThat(extracted).contains("Users must sign in with their company email address and a password that meets the current complexity policy.");
        assertThat(extracted).contains("Locked accounts can be restored by the support team after identity verification.");
        assertThat(extracted).contains("Severity three incidents are minor defects with available workarounds.");
        assertThat(extracted).contains("- First list item\n\n- Second list item");
    }
}
