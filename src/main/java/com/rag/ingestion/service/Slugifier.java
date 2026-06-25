package com.rag.ingestion.service;

final class Slugifier {

    private Slugifier() {
    }

    static String slugify(String value) {
        String slug = value.toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        return slug.isBlank() ? "source" : slug;
    }
}
