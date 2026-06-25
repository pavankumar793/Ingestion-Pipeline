package com.rag.ingestion.service;

import com.rag.ingestion.service.model.ExtractedDocument;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.springframework.stereotype.Service;

@Service
public class TextExtractionService {

    private final Tika tika = new Tika();

    public ExtractedDocument extract(String fileName, String contentType, InputStream inputStream) throws IOException, TikaException {
        String text = tika.parseToString(inputStream);
        return new ExtractedDocument(fileName, contentType, normalize(text));
    }

    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[ \\t]+\\n", "\n")
                .trim();
        return String.join("\n\n", mergeContinuationBlocks(blocks(normalized))).trim();
    }

    private static List<String> blocks(String text) {
        List<String> blocks = new ArrayList<>();
        StringBuilder block = new StringBuilder();

        for (String rawLine : text.split("\\n", -1)) {
            String line = rawLine.trim();
            if (line.isBlank()) {
                appendBlock(blocks, block);
            } else if (shouldStayOnOwnLine(line)) {
                appendBlock(blocks, block);
                blocks.add(line);
            } else {
                if (!block.isEmpty() && looksLikeStandaloneTitle(block.toString())) {
                    appendBlock(blocks, block);
                }
                if (!block.isEmpty()) {
                    block.append(' ');
                }
                block.append(line);
            }
        }

        appendBlock(blocks, block);
        return blocks;
    }

    private static List<String> mergeContinuationBlocks(List<String> blocks) {
        List<String> merged = new ArrayList<>();
        for (String block : blocks) {
            if (!merged.isEmpty() && shouldMergeAcrossBlankLine(merged.getLast(), block)) {
                int lastIndex = merged.size() - 1;
                merged.set(lastIndex, merged.get(lastIndex) + " " + block);
            } else {
                merged.add(block);
            }
        }
        return merged;
    }

    private static void appendBlock(List<String> blocks, StringBuilder block) {
        if (block.isEmpty()) {
            return;
        }
        blocks.add(block.toString());
        block.setLength(0);
    }

    private static boolean shouldMergeAcrossBlankLine(String previous, String current) {
        if (shouldStayOnOwnLine(previous) || shouldStayOnOwnLine(current)) {
            return false;
        }
        if (endsSentence(previous)) {
            return false;
        }
        return current.matches("^[a-z].*");
    }

    private static boolean endsSentence(String value) {
        return value.matches(".*[.!?:;)]$");
    }

    private static boolean looksLikeStandaloneTitle(String value) {
        String[] words = value.trim().split("\\s+");
        if (words.length == 0 || words.length > 8 || value.endsWith(".")) {
            return false;
        }
        int titleWords = 0;
        for (String word : words) {
            String cleaned = word.replaceAll("^[^A-Za-z0-9]+|[^A-Za-z0-9]+$", "");
            if (cleaned.length() <= 2 || cleaned.matches("(?i)and|or|the|to|of|for|in|on|with")) {
                continue;
            }
            if (!Character.isUpperCase(cleaned.charAt(0))) {
                return false;
            }
            titleWords++;
        }
        return titleWords > 0;
    }

    private static boolean shouldStayOnOwnLine(String line) {
        return line.startsWith("#")
                || line.matches("^[-*]\\s+.+")
                || line.matches("^[0-9]+[.)]\\s+.+");
    }
}
