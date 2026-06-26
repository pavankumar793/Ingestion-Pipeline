package com.rag.ingestion.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.rag.ingestion.service.model.UrlDocument;
import org.junit.jupiter.api.Test;

class UrlContentServiceTest {

    private final UrlContentService service = new UrlContentService();

    @Test
    void extractsReadableTextFromHtmlAndSkipsNavigationNoise() {
        UrlDocument document = service.extract("https://example.com/wiki/support", "text/html", """
                <html>
                  <head><title>Support Wiki</title><script>ignored()</script></head>
                  <body>
                    <nav>Home Docs Admin</nav>
                    <main>
                      <h1>Support Ownership Matrix</h1>
                      <p>This page explains who owns support workflows.</p>
                      <p>Toolâ€”Podman text is repaired.</p>
                      <p>Need some help?</p>
                      <ul><li>Review documents before publishing.</li></ul>
                      <table>
                        <tr><th>Area</th><th>Primary Owner</th><th>Review Frequency</th></tr>
                        <tr><td>Account access</td><td>Identity Support</td><td>Monthly</td></tr>
                      </table>
                    </main>
                    <footer>Copyright</footer>
                  </body>
                </html>
                """);

        assertThat(document.sourceName()).isEqualTo("support-wiki");
        assertThat(document.text()).contains("Support Ownership Matrix");
        assertThat(document.text()).contains("This page explains who owns support workflows.");
        assertThat(document.text()).contains("Tool-Podman text is repaired.");
        assertThat(document.text()).contains("- Review documents before publishing.");
        assertThat(document.text()).contains("Area Primary Owner Review Frequency");
        assertThat(document.text()).doesNotContain("Home Docs Admin");
        assertThat(document.text()).doesNotContain("Need some help?");
        assertThat(document.text()).doesNotContain("Copyright");
    }

    @Test
    void prefersMainContentOverBodyChrome() {
        UrlDocument document = service.extract("https://podman.io/features", "text/html", """
                <html>
                  <head><title>Podman</title></head>
                  <body>
                    <section><h2>Join Podman's Community</h2><p>Need some help?</p></section>
                    <main>
                      <h1>Podman Features</h1>
                      <p>Podman provides command-line tools for running containers.</p>
                    </main>
                  </body>
                </html>
                """);

        assertThat(document.sourceName()).isEqualTo("podman-features");
        assertThat(document.text()).contains("Podman Features");
        assertThat(document.text()).contains("Podman provides command-line tools for running containers.");
        assertThat(document.text()).doesNotContain("Join Podman's Community");
    }

    @Test
    void acceptsPlainTextContent() {
        UrlDocument document = service.extract("https://example.com/plain.txt", "text/plain", "Plain page content.");

        assertThat(document.sourceName()).isEqualTo("example-com-plain-txt");
        assertThat(document.text()).isEqualTo("Plain page content.");
    }
}
