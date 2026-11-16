# mailsync

`mailsync` is a robust, two-phase Java CLI pipeline designed to migrate large email archives (hundreds of thousands of messages) from **Fastmail** to **Google Workspace (Gmail)** with complete state tracking and resumption guarantees.

---

## Architecture & Features

- **JMAP Protocol Over HTTP/2**: Uses Fastmail's modern JMAP API rather than legacy IMAP to query message sets and fetch raw RFC 822 `.eml` blobs.
- **RFC 822 `users.messages.import`**: Ingests emails directly into Gmail's storage engine. Timestamps, original sender/recipient headers, and multi-domain aliases (`carlhicks.org`, `carlhicks.me`, `linux.com`, `hicksca.dev`) are preserved without generating outgoing SMTP traffic or inbox notifications.
- **SQLite Idempotency & Error Handling (`mail_archive/migration.db`)**: Every downloaded blob and upload operation is tracked. If interrupted by network timeouts or rate limits, the migrator resumes instantly without downloading or uploading duplicate emails. It also gracefully catches and skips Google API HTTP 400 blocks (e.g., banned attachments) to prevent pipeline crashes.
- **Folder to Label Mapping**: Maps Fastmail mailboxes (including multi-mailbox assignments) to Gmail labels, creating custom labels dynamically via the Gmail API when needed.
- **Legacy Email Organization**: Programmatically sorts migrated historical emails into nested Year/Domain labels (e.g., `2017/carlhicks.me`) using Gmail search queries and batch modifications to safely bypass API quota limits.
- **Quota & Pacing Management**: Implements backoff retry loops on blob downloads and paces Gmail imports to remain within Google Workspace write limits (250 units/sec limit).

---

## Tech Stack

- **Runtime**: Java 21 (Amazon Corretto 21)
- **Build Tool**: Gradle (Kotlin DSL)
- **HTTP / JSON**: OkHttp 4, Jackson Databind
- **Google Cloud**: Google API Client (`google-api-services-gmail`), Google OAuth 2.0
- **Database**: SQLite JDBC

---

## Prerequisites

1. **Fastmail API Token**: Generated with full Mail access under Fastmail Settings → Password & Security → API Tokens.
2. **Google Cloud OAuth Desktop Client**:
   - Create a Google Cloud Project with the **Gmail API** enabled.
   - Configure an OAuth consent screen (Internal or External with your user added as a test user).
   - Create an **OAuth 2.0 Client ID** of application type **Desktop app**.
   - Download the JSON credential file and place it at:
     ```text
     src/main/resources/credentials.json
     ```
   - *Note on Scopes:* Ensure your code requests `GMAIL_INSERT`, `GMAIL_LABELS`, and `GMAIL_MODIFY` scopes to support both the initial upload and the post-migration batch tagging.

---

## Configuration & Usage

### 1. Set the Fastmail Token

Export your Fastmail API token in your shell environment:

```bash
export FASTMAIL_API_TOKEN="your-fastmail-api-token"
```

### 2. Run the Migrator

Build and run using the Gradle wrapper:

```bash
./gradlew run
```

On first run, the CLI will open your default browser to authenticate with your Google Workspace account and store your refresh token under `tokens/`. *(If you update API scopes later, you must delete the `tokens/` directory to force a re-authentication prompt).*

Depending on how your `MailMigratorCli` main method is configured, the pipeline proceeds in these stages:
1. **Archive Phase**: Fetches and downloads all raw `.eml` files into `mail_archive/` and records progress in SQLite.
2. **Upload Phase**: Reads unuploaded messages from SQLite and streams them into Gmail via `users.messages.import`.
3. **Legacy Organization Phase**: (Optional) Scans the uploaded historical emails (e.g., 2017–2025) and batch-applies nested `Year/Domain` labels based on standard Gmail search queries.

---

## License

This project is licensed under the [ISC License](LICENSE)
