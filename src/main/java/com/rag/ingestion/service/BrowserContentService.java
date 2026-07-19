package com.rag.ingestion.service;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitUntilState;
import com.rag.ingestion.config.IngestionProperties;
import com.rag.ingestion.service.model.UrlDocument;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.stereotype.Service;

@Service
public class BrowserContentService {

    private final IngestionProperties properties;
    private final UrlContentService urlContentService;

    public BrowserContentService(IngestionProperties properties, UrlContentService urlContentService) {
        this.properties = properties;
        this.urlContentService = urlContentService;
    }

    public UrlDocument fetch(String url, String browserOverride) {
        if (properties.browser() == null || !properties.browser().enabled()) {
            throw new IngestionValidationException("Browser ingestion is disabled.");
        }

        IngestionProperties.Browser browser = properties.browser();
        String provider = provider(browserOverride, browser.provider());
        Path profilePath = browser.profilePath().resolve(provider);

        try (Playwright playwright = Playwright.create();
             BrowserContext context = browserType(playwright, provider)
                     .launchPersistentContext(profilePath, launchOptions(browser, provider))) {
            Page page = context.pages().isEmpty() ? context.newPage() : context.pages().getFirst();
            page.navigate(url, new Page.NavigateOptions()
                    .setTimeout(Duration.ofSeconds(browser.interactiveTimeoutSeconds()).toMillis())
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            waitForReadableTargetPage(page, url, browser);
            String html = page.content();
            return urlContentService.extract(page.url(), "text/html", html);
        }
    }

    private static BrowserType.LaunchPersistentContextOptions launchOptions(IngestionProperties.Browser browser, String provider) {
        BrowserType.LaunchPersistentContextOptions options = new BrowserType.LaunchPersistentContextOptions()
                .setHeadless(browser.headless())
                .setTimeout(Duration.ofSeconds(browser.navigationTimeoutSeconds()).toMillis());
        if ("chrome".equals(provider)) {
            options.setChannel("chrome");
        } else if ("edge".equals(provider)) {
            options.setChannel("msedge");
        } else if ("custom".equals(provider)) {
            if (browser.executablePath() == null || browser.executablePath().isBlank()) {
                throw new IngestionValidationException("Set ingestion.browser.executable-path when browser is custom.");
            }
            options.setExecutablePath(Path.of(browser.executablePath()));
        }
        return options;
    }

    private static BrowserType browserType(Playwright playwright, String provider) {
        return switch (provider) {
            case "firefox" -> playwright.firefox();
            case "webkit" -> playwright.webkit();
            case "default", "chrome", "edge", "custom" -> playwright.chromium();
            default -> throw new IngestionValidationException("Unsupported browser provider: " + provider);
        };
    }

    private static String provider(String requestProvider, String configuredProvider) {
        String provider = requestProvider == null || requestProvider.isBlank() ? configuredProvider : requestProvider;
        if (provider == null || provider.isBlank()) {
            return "default";
        }
        return provider.trim().toLowerCase();
    }

    private static void waitForReadableTargetPage(Page page, String requestedUrl, IngestionProperties.Browser browser) {
        long deadline = System.nanoTime() + Duration.ofSeconds(browser.interactiveTimeoutSeconds()).toNanos();
        while (System.nanoTime() < deadline) {
            try {
                page.waitForLoadState(LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions()
                        .setTimeout(Duration.ofSeconds(5).toMillis()));
            } catch (TimeoutError ignored) {
                // Older SSO pages can keep connections open; polling visible text is more reliable.
            }
            if (isSameTarget(requestedUrl, page.url()) && visibleTextLength(page) >= 80) {
                return;
            }
            page.waitForTimeout(1000);
        }
        throw new IngestionValidationException("Timed out waiting for browser login and readable page content.");
    }

    private static int visibleTextLength(Page page) {
        try {
            Locator body = page.locator("body");
            return body.innerText().trim().length();
        } catch (RuntimeException exception) {
            return 0;
        }
    }

    private static boolean isSameTarget(String requestedUrl, String currentUrl) {
        try {
            URI requested = new URI(requestedUrl);
            URI current = new URI(currentUrl);
            return same(requested.getHost(), current.getHost()) && same(normalizePath(requested), normalizePath(current));
        } catch (URISyntaxException exception) {
            return requestedUrl.equals(currentUrl);
        }
    }

    private static String normalizePath(URI uri) {
        String path = uri.getPath();
        return path == null || path.isBlank() ? "/" : path.replaceAll("/+$", "");
    }

    private static boolean same(String left, String right) {
        return left == null ? right == null : left.equalsIgnoreCase(right);
    }
}
