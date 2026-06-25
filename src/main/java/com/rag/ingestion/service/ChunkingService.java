package com.rag.ingestion.service;

import com.rag.ingestion.config.IngestionProperties;
import com.rag.ingestion.service.model.DocumentChunk;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class ChunkingService {

    private final IngestionProperties properties;

    public ChunkingService(IngestionProperties properties) {
        this.properties = properties;
    }

    public List<DocumentChunk> chunk(String sourceName, String text) {
        List<DocumentChunk> chunks = new ArrayList<>();
        appendSectionChunks(sourceName, text, chunks);
        return chunks;
    }

    private void appendSectionChunks(String sourceName, String section, List<DocumentChunk> chunks) {
        List<String> paragraphs = paragraphs(section);
        List<String> current = new ArrayList<>();
        int currentWords = 0;

        for (String paragraph : paragraphs) {
            int paragraphWords = wordCount(paragraph);
            if (!current.isEmpty() && currentWords + paragraphWords > properties.chunk().maxWords()) {
                addChunk(sourceName, current, chunks);
                current = overlapFrom(current);
                currentWords = current.stream().mapToInt(ChunkingService::wordCount).sum();
            }
            current.add(paragraph);
            currentWords += paragraphWords;
        }

        if (!current.isEmpty()) {
            addChunk(sourceName, current, chunks);
        }
    }

    private void addChunk(String sourceName, List<String> paragraphs, List<DocumentChunk> chunks) {
        String content = String.join("\n\n", paragraphs).trim();
        if (content.isBlank()) {
            return;
        }
        int index = chunks.size() + 1;
        String id = sourceName + "-" + String.format("%03d", index);
        chunks.add(new DocumentChunk(id, index, firstHeading(content), content));
    }

    private List<String> overlapFrom(List<String> paragraphs) {
        if (properties.chunk().overlapWords() <= 0) {
            return new ArrayList<>();
        }
        List<String> overlap = new ArrayList<>();
        int words = 0;
        for (int index = paragraphs.size() - 1; index >= 0; index--) {
            String paragraph = paragraphs.get(index);
            int paragraphWords = wordCount(paragraph);
            if (!overlap.isEmpty() && words + paragraphWords > properties.chunk().overlapWords()) {
                break;
            }
            overlap.add(0, paragraph);
            words += paragraphWords;
        }
        return overlap;
    }

    private static List<String> paragraphs(String text) {
        List<Block> blocks = new ArrayList<>();
        for (String paragraph : text.split("\\n\\s*\\n")) {
            String cleaned = paragraph.trim();
            if (!cleaned.isBlank()) {
                blocks.add(new Block(cleaned, looksLikeHeading(cleaned)));
            }
        }
        return toMarkdownBlocks(blocks);
    }

    private static List<String> toMarkdownBlocks(List<Block> blocks) {
        List<String> paragraphs = new ArrayList<>();
        int currentSectionLevel = 0;
        int previousHeadingLevel = 0;

        for (int index = 0; index < blocks.size(); index++) {
            TableMatch table = tableAt(blocks, index);
            if (table != null) {
                paragraphs.add(table.markdown());
                index = table.endIndex();
                previousHeadingLevel = 0;
                continue;
            }

            Block block = blocks.get(index);
            if (!block.heading()) {
                paragraphs.add(block.text());
                previousHeadingLevel = 0;
                continue;
            }

            int level = headingLevel(block.text(), index, blocks, currentSectionLevel, previousHeadingLevel);
            paragraphs.add(markdownHeading(block.text(), level));
            if (level <= 2) {
                currentSectionLevel = level;
            }
            previousHeadingLevel = level;
        }
        return paragraphs;
    }

    private static TableMatch tableAt(List<Block> blocks, int startIndex) {
        if (startIndex + 2 >= blocks.size()) {
            return null;
        }

        List<String> header = splitHeaderColumns(blocks.get(startIndex).text());
        if (header.size() < 2) {
            return null;
        }

        List<List<String>> rows = new ArrayList<>();
        int index = startIndex + 1;
        while (index < blocks.size()) {
            Block rowBlock = blocks.get(index);
            if (rowBlock.text().contains(".") || rowBlock.text().length() > 120) {
                break;
            }
            List<String> row = splitRowColumns(rowBlock.text(), header.size());
            if (row.size() != header.size()) {
                break;
            }
            rows.add(row);
            index++;
        }

        if (rows.size() < 2) {
            return null;
        }

        return new TableMatch(markdownTable(header, rows), index - 1);
    }

    private static List<String> splitHeaderColumns(String value) {
        List<String> words = words(value);
        if (words.size() < 2 || words.size() > 12) {
            return List.of();
        }

        List<String> columns = new ArrayList<>();
        List<String> current = new ArrayList<>();
        for (String word : words) {
            current.add(word);
            if (isHeaderBoundary(word)) {
                columns.add(String.join(" ", current));
                current.clear();
            }
        }
        if (!current.isEmpty()) {
            columns.add(String.join(" ", current));
        }
        return columns.size() >= 2 ? columns : List.of();
    }

    private static List<String> splitRowColumns(String value, int columnCount) {
        List<String> words = words(value);
        if (words.size() < columnCount) {
            return List.of();
        }
        if (columnCount == 2) {
            return List.of(words.getFirst(), String.join(" ", words.subList(1, words.size())));
        }
        if (columnCount == 3) {
            int middleStart = firstTitleCaseRun(words);
            if (middleStart <= 0 || middleStart >= words.size() - 1) {
                return List.of();
            }
            return List.of(
                    String.join(" ", words.subList(0, middleStart)),
                    String.join(" ", words.subList(middleStart, words.size() - 1)),
                    words.getLast()
            );
        }
        return splitEvenly(words, columnCount);
    }

    private static List<String> splitEvenly(List<String> words, int columnCount) {
        if (words.size() < columnCount) {
            return List.of();
        }
        List<String> columns = new ArrayList<>();
        int wordsPerColumn = Math.max(1, words.size() / columnCount);
        int cursor = 0;
        for (int index = 0; index < columnCount; index++) {
            int remainingColumns = columnCount - index;
            int remainingWords = words.size() - cursor;
            int end = index == columnCount - 1 ? words.size() : cursor + Math.min(wordsPerColumn, remainingWords - remainingColumns + 1);
            columns.add(String.join(" ", words.subList(cursor, end)));
            cursor = end;
        }
        return columns;
    }

    private static int firstTitleCaseRun(List<String> words) {
        for (int index = 1; index < words.size() - 1; index++) {
            if (startsWithUppercase(words.get(index)) && startsWithUppercase(words.get(index + 1))) {
                return index;
            }
        }
        return -1;
    }

    private static List<String> words(String value) {
        return Arrays.stream(value.trim().split("\\s+"))
                .filter(word -> !word.isBlank())
                .toList();
    }

    private static boolean isHeaderBoundary(String word) {
        return TABLE_HEADER_BOUNDARY_WORDS.contains(cleanWord(word).toLowerCase());
    }

    private static boolean startsWithUppercase(String word) {
        String cleaned = cleanWord(word);
        return !cleaned.isBlank() && Character.isUpperCase(cleaned.charAt(0));
    }

    private static String cleanWord(String word) {
        return word.replaceAll("^[^A-Za-z0-9]+|[^A-Za-z0-9]+$", "");
    }

    private static String markdownTable(List<String> header, List<List<String>> rows) {
        StringBuilder builder = new StringBuilder();
        appendTableRow(builder, header);
        builder.append("|");
        for (int index = 0; index < header.size(); index++) {
            builder.append(" --- |");
        }
        builder.append('\n');
        for (List<String> row : rows) {
            appendTableRow(builder, row);
        }
        return builder.toString().trim();
    }

    private static void appendTableRow(StringBuilder builder, List<String> cells) {
        builder.append("|");
        for (String cell : cells) {
            builder.append(' ').append(cell.replace("|", "\\|")).append(" |");
        }
        builder.append('\n');
    }

    private static int headingLevel(String heading, int index, List<Block> blocks, int currentSectionLevel, int previousHeadingLevel) {
        if (heading.startsWith("#")) {
            return existingMarkdownHeadingLevel(heading);
        }
        if (index == 0) {
            return 1;
        }
        if (nextBlockIsHeading(index, blocks)) {
            return 2;
        }
        if (previousBlockIsHeading(index, blocks)) {
            return Math.min(previousHeadingLevel + 1, 4);
        }
        if (currentSectionLevel > 0) {
            return Math.min(currentSectionLevel + 1, 4);
        }
        return 2;
    }

    private static String markdownHeading(String heading, int level) {
        if (heading.startsWith("#")) {
            return heading;
        }
        return "#".repeat(level) + " " + heading;
    }

    private static int existingMarkdownHeadingLevel(String heading) {
        int level = 0;
        while (level < heading.length() && heading.charAt(level) == '#') {
            level++;
        }
        return Math.max(1, Math.min(level, 4));
    }

    private static boolean nextBlockIsHeading(int index, List<Block> blocks) {
        return index + 1 < blocks.size() && blocks.get(index + 1).heading();
    }

    private static boolean previousBlockIsHeading(int index, List<Block> blocks) {
        return index > 0 && blocks.get(index - 1).heading();
    }

    private static boolean looksLikeHeading(String text) {
        String line = text.trim();
        if (line.isBlank() || line.length() > 100 || line.endsWith(".")) {
            return false;
        }
        if (line.startsWith("#")) {
            return true;
        }
        if (line.matches("^[0-9]+(\\.[0-9]+)*\\s+.+")) {
            return true;
        }
        long letters = line.chars().filter(Character::isLetter).count();
        long uppercase = line.chars().filter(Character::isUpperCase).count();
        return (letters > 0 && uppercase >= Math.max(letters * 0.7, 3)) || looksLikeTitleCaseHeading(line);
    }

    private static boolean looksLikeTitleCaseHeading(String line) {
        String[] words = line.split("\\s+");
        if (words.length > 8) {
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

    private static String firstHeading(String content) {
        for (String line : content.split("\\n")) {
            if (line.startsWith("#")) {
                return line.replaceFirst("^#+\\s*", "").trim();
            }
        }
        return null;
    }

    private static int wordCount(String value) {
        String trimmed = value.trim();
        return trimmed.isBlank() ? 0 : trimmed.split("\\s+").length;
    }

    private static final Set<String> TABLE_HEADER_BOUNDARY_WORDS = Set.of(
            "area",
            "date",
            "description",
            "frequency",
            "id",
            "name",
            "notes",
            "owner",
            "role",
            "status",
            "team",
            "type",
            "value"
    );

    private record Block(String text, boolean heading) {
    }

    private record TableMatch(String markdown, int endIndex) {
    }
}
