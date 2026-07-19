# Ingestion Pipeline

Spring Boot API that converts `.txt`, `.docx`, and text-based `.pdf` files into reviewable Markdown chunks for a RAG app.

## MVP Flow

```text
POST /api/ingestions
  -> upload up to 10 files with multipart/form-data
  -> extract readable text
  -> chunk content for RAG
  -> write Markdown chunks to docs-unstaged/{batch-id}/{source-name}
  -> write manifest.json and manifest.md for review
```

The RAG app should consume only reviewed files promoted out of `docs-unstaged`.

## Run

```bash
mvn spring-boot:run
```

## Upload Example

```bash
curl -F "files=@handbook.pdf" -F "files=@policy.docx" http://localhost:8082/api/ingestions
```

## URL Ingestion Example

```bash
curl -X POST http://localhost:8082/api/ingestions/urls \
  -H "Content-Type: application/json" \
  -d "{\"urls\":[\"https://example.com/wiki/support\"]}"
```

URL ingestion fetches text-based pages, removes common page chrome such as scripts, nav, headers, footers, forms, and sidebars, then extracts readable text from headings, paragraphs, list items, and tables. The same chunking, manifest, local output, and optional GitHub publishing flow is used for URL inputs.

For pages protected by a bearer token, include `bearerToken`. The token is used only as an HTTP `Authorization: Bearer ...` header during fetch and is not written to chunks or manifests.

```bash
curl -X POST http://localhost:8082/api/ingestions/urls \
  -H "Content-Type: application/json" \
  -d "{\"urls\":[\"https://example.com/private/wiki\"],\"bearerToken\":\"your_token\"}"
```

For browser-authenticated pages, copy the request `Cookie` header from DevTools and pass it as `cookieHeader`. The cookie is used only as the HTTP `Cookie` header during fetch and is not written to chunks or manifests. Usually use either `bearerToken` or `cookieHeader`, depending on how the page is authenticated.

```bash
curl -X POST http://localhost:8082/api/ingestions/urls \
  -H "Content-Type: application/json" \
  -d "{\"urls\":[\"https://wiki.company.com/page\"],\"cookieHeader\":\"JSESSIONID=...; PF=...; other=...\"}"
```

## Browser-Assisted URL Ingestion

For SSO-protected or old wiki pages that do not work with plain HTTP fetch, use browser-assisted ingestion. The app opens a real browser session, waits while you complete login if needed, reads the rendered page, then writes the same Markdown chunks and manifests.

```bash
curl -X POST http://localhost:8082/api/ingestions/browser-urls \
  -H "Content-Type: application/json" \
  -d "{\"urls\":[\"https://wiki.company.com/page\"],\"browser\":\"default\"}"
```

Supported browser values:

- `default`: Playwright Chromium
- `chrome`: installed Google Chrome
- `edge`: installed Microsoft Edge
- `firefox`: Playwright Firefox
- `webkit`: Playwright WebKit, closest to Safari behavior
- `custom`: configured executable path, useful for Chromium-based enterprise browsers such as Island when automation is allowed

Browser settings are configurable:

```yaml
ingestion:
  browser:
    enabled: true
    provider: default
    executable-path:
    profile-path: browser-profile
    headless: false
    navigation-timeout-seconds: 60
    interactive-timeout-seconds: 300
```

For the first local run, install Playwright browser binaries if `default`, `firefox`, or `webkit` is used:

```bash
mvn exec:java -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="install"
```

If you use `chrome`, `edge`, or `custom`, install that browser normally on the machine and set `ingestion.browser.executable-path` only for `custom`. For cloud deployment, use a persistent volume for `browser-profile`; otherwise login sessions disappear after restart.

## Defaults

- Max files per request: 10
- Max URLs per request: 10
- Max file size: 25MB
- Output folder: `docs-unstaged`
- Supported extensions: `.txt`, `.docx`, `.pdf`

## GitHub Publishing

GitHub publishing is disabled by default. When enabled, successful Markdown chunks are committed to the RAG app repository and a ready-for-review PR is opened.

Default target:

```text
pavankumar793/slack-rag-assistant
branch: main
folder: docs
```

Run with GitHub publishing:

```bash
GITHUB_PERSONAL_ACCESS_TOKEN=your_token mvn spring-boot:run -Dspring-boot.run.arguments="--ingestion.github.enabled=true"
```

Or create a local `.env` file from `.env.example` and load it before running the app. Keep `.env` private; it is ignored by Git.

Only successful `.md` chunk files are published to stable paths:

```text
docs/{source-name}/chunk-001.md
```

Skipped files are not published. Failed files do not block the PR, but they are listed in the PR body and API response.
