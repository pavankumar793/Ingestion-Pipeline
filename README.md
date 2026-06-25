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
curl -F "files=@handbook.pdf" -F "files=@policy.docx" http://localhost:8080/api/ingestions
```

## Defaults

- Max files per request: 10
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
GITHUB_TOKEN=your_token mvn spring-boot:run -Dspring-boot.run.arguments="--ingestion.github.enabled=true"
```

Or create a local `.env` file from `.env.example` and load it before running the app. Keep `.env` private; it is ignored by Git.

Only successful `.md` chunk files are published to stable paths:

```text
docs/{source-name}/chunk-001.md
```

Skipped files are not published. Failed files do not block the PR, but they are listed in the PR body and API response.
