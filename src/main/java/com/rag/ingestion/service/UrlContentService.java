package com.rag.ingestion.service;

import com.rag.ingestion.service.model.UrlDocument;
import com.rag.ingestion.service.model.UrlFetchOptions;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Service;

@Service
public class UrlContentService {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public UrlDocument fetch(String url) throws IOException, InterruptedException {
        return fetch(url, UrlFetchOptions.none());
    }

    public UrlDocument fetch(String url, UrlFetchOptions options) throws IOException, InterruptedException {
        URI uri = validHttpUri(url);
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", "ingestion-pipeline/0.0.1")
                .GET();
        if (options != null && options.bearerToken() != null && !options.bearerToken().isBlank()) {
            requestBuilder.header("Authorization", "Bearer " + options.bearerToken().trim());
        }
        if (options != null && options.cookieHeader() != null && !options.cookieHeader().isBlank()) {
            requestBuilder.header("Cookie", normalizeCookieHeader(options.cookieHeader()));
        }
        HttpRequest request = requestBuilder.build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IngestionValidationException("URL returned HTTP " + response.statusCode() + ": " + url);
        }
        String contentType = response.headers().firstValue("content-type").orElse("text/html");
        return extract(url, contentType, response.body());
    }

    UrlDocument extract(String url, String contentType, String body) {
        if (body == null || body.isBlank()) {
            throw new IngestionValidationException("URL returned no readable content: " + url);
        }
        String text;
        String sourceName;
        if (contentType != null && contentType.toLowerCase().contains("html")) {
            Document document = Jsoup.parse(body, url);
            removeNonContent(document);
            text = readableText(document);
            sourceName = sourceName(document, url);
        } else if (contentType == null || contentType.toLowerCase().startsWith("text/")) {
            text = cleanText(body);
            sourceName = Slugifier.slugify(uriSlug(url));
        } else {
            throw new IngestionValidationException("URL content type is not text/html or text/plain: " + contentType);
        }
        if (text.isBlank()) {
            throw new IngestionValidationException("URL did not contain readable text content: " + url);
        }
        return new UrlDocument(url, sourceName, text, Hashing.sha256Hex(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static String readableText(Document document) {
        List<String> blocks = new ArrayList<>();
        if (!document.title().isBlank()) {
            blocks.add(cleanText(document.title()));
        }
        Element root = contentRoot(document);
        if (root != null) {
            appendBlocks(root, blocks);
        }
        return String.join("\n\n", blocks).trim();
    }

    private static void removeNonContent(Document document) {
        document.select("script, style, noscript, svg, canvas, iframe, nav, header, footer, aside, form").remove();
        document.select("[aria-label~=breadcrumb|pagination], [class~=breadcrumb|pagination|navbar|menu|toc|sidebar|footer|header|community|promo|blog|download|install|copyright|newsletter|social], [id~=breadcrumb|pagination|navbar|menu|toc|sidebar|footer|header|community|promo|blog|download|install|copyright|newsletter|social]").remove();
    }

    private static Element contentRoot(Document document) {
        for (String selector : List.of("main", "article", "[role=main]", ".content", ".main-content", ".markdown-body", ".docs-content")) {
            Element element = document.selectFirst(selector);
            if (element != null && element.text().length() > 100) {
                return element;
            }
        }
        return document.body();
    }

    private static void appendBlocks(Element element, List<String> blocks) {
        String tag = element.tagName().toLowerCase();
        if (List.of("h1", "h2", "h3", "h4", "h5", "h6", "p").contains(tag)) {
            addBlock(blocks, element.text());
            return;
        }
        if ("li".equals(tag)) {
            addBlock(blocks, "- " + element.text());
            return;
        }
        if ("table".equals(tag)) {
            appendTable(element, blocks);
            return;
        }
        for (Element child : element.children()) {
            appendBlocks(child, blocks);
        }
    }

    private static void appendTable(Element table, List<String> blocks) {
        for (Element row : table.select("tr")) {
            List<String> cells = row.select("th, td").eachText();
            if (!cells.isEmpty()) {
                addBlock(blocks, String.join(" ", cells));
            }
        }
    }

    private static void addBlock(List<String> blocks, String text) {
        String cleaned = cleanText(text);
        if (!cleaned.isBlank() && !looksLikeCta(cleaned) && blocks.stream().noneMatch(cleaned::equals)) {
            blocks.add(cleaned);
        }
    }

    private static boolean looksLikeCta(String text) {
        String lower = text.toLowerCase();
        if (lower.length() <= 80 && List.of(
                "join ",
                "need some help",
                "check out more",
                "installation instructions",
                "documentation",
                "troubleshooting guide",
                "have fun coloring",
                "learn more",
                "get started",
                "download"
        ).stream().anyMatch(lower::contains)) {
            return true;
        }
        return lower.matches(".*\\b(blog|community|newsletter|copyright)\\b.*") && lower.length() <= 120;
    }

    private static String cleanText(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("â€”", "-")
                .replace("â€“", "-")
                .replace("â€˜", "'")
                .replace("â€™", "'")
                .replace("â€œ", "\"")
                .replace("â€�", "\"")
                .replace("Â", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String normalizeCookieHeader(String cookieHeader) {
        return cookieHeader
                .replaceAll("[\\r\\n]+", " ")
                .replaceAll("\\s*;\\s*", "; ")
                .replaceAll("\\s{2,}", " ")
                .trim();
    }

    private static URI validHttpUri(String value) {
        try {
            URI uri = new URI(value == null ? "" : value.trim());
            if (!List.of("http", "https").contains(uri.getScheme())) {
                throw new IngestionValidationException("Only http and https URLs are supported: " + value);
            }
            return uri;
        } catch (URISyntaxException exception) {
            throw new IngestionValidationException("Invalid URL: " + value);
        }
    }

    private static String uriSlug(String url) {
        try {
            URI uri = new URI(url);
            String path = uri.getPath() == null || uri.getPath().isBlank() ? "home" : uri.getPath();
            return uri.getHost() + "-" + path.replaceAll("/+", "-");
        } catch (URISyntaxException exception) {
            return "web-page";
        }
    }

    private static String sourceName(Document document, String url) {
        String title = document.title();
        String path = uriPathSlug(url);
        if (title == null || title.isBlank()) {
            return Slugifier.slugify(uriSlug(url));
        }
        String titleSlug = Slugifier.slugify(title);
        if (path.isBlank() || titleSlug.contains(path)) {
            return titleSlug;
        }
        return Slugifier.slugify(titleSlug + "-" + path);
    }

    private static String uriPathSlug(String url) {
        try {
            URI uri = new URI(url);
            String path = uri.getPath();
            if (path == null || path.isBlank() || "/".equals(path)) {
                return "";
            }
            String[] segments = path.replaceAll("^/+|/+$", "").split("/+");
            return Slugifier.slugify(segments[segments.length - 1]);
        } catch (URISyntaxException exception) {
            return "";
        }
    }
}
