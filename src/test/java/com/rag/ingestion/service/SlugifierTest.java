package com.rag.ingestion.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SlugifierTest {

    @Test
    void createsStableFolderSafeSlug() {
        assertThat(Slugifier.slugify("Employee Handbook v1.2")).isEqualTo("employee-handbook-v1-2");
    }

    @Test
    void fallsBackWhenNoUsableCharactersExist() {
        assertThat(Slugifier.slugify("___")).isEqualTo("source");
    }
}
