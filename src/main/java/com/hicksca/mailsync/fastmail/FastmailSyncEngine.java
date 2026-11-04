package com.hicksca.mailsync.fastmail;

import com.fasterxml.jackson.databind.JsonNode;
import com.hicksca.mailsync.storage.MigrationDb;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;

public class FastmailSyncEngine {
    private final FastmailJmapClient client;
    private final MigrationDb db;
    private final Path archiveDir;

    public FastmailSyncEngine(FastmailJmapClient client, MigrationDb db, Path archiveDir) {
        this.client = client;
        this.db = db;
        this.archiveDir = archiveDir;
    }

    public Map<String, String> getMailboxMap() throws IOException {
        Map<String, String> map = new HashMap<>();
        JsonNode response = client.executeCall("Mailbox/get", Map.of("accountId", client.getAccountId()));
        if (response.has("list")) {
            for (JsonNode mb : response.get("list")) {
                map.put(mb.get("id").asText(), mb.get("name").asText());
            }
        }
        return map;
    }

    public void runArchive() throws IOException, SQLException {
        Files.createDirectories(archiveDir);
        int position = 0;
        int limit = 250; // Increased batch size for faster discovery
        boolean hasMore = true;

        System.out.println("Starting Fastmail email discovery and download...");

        while (hasMore) {
            Map<String, Object> queryArgs = Map.of(
                    "accountId", client.getAccountId(),
                    "position", position,
                    "limit", limit,
                    "calculateTotal", true
            );

            JsonNode queryResponse = client.executeCall("Email/query", queryArgs);
            JsonNode idsNode = queryResponse.get("ids");
            if (idsNode == null || idsNode.isEmpty()) {
                break;
            }

            List<String> idsToFetch = new ArrayList<>();
            for (JsonNode idNode : idsNode) {
                String id = idNode.asText();
                // Check local DB first before requesting metadata from Fastmail
                if (!db.isArchived(id)) {
                    idsToFetch.add(id);
                }
            }

            // Only make an Email/get call if there are new unarchived emails in this page
            if (!idsToFetch.isEmpty()) {
                Map<String, Object> getArgs = Map.of(
                        "accountId", client.getAccountId(),
                        "ids", idsToFetch,
                        "properties", List.of("id", "blobId", "mailboxIds")
                );

                JsonNode getResponse = client.executeCall("Email/get", getArgs);
                if (getResponse.has("list")) {
                    for (JsonNode email : getResponse.get("list")) {
                        String id = email.get("id").asText();
                        String blobId = email.get("blobId").asText();

                        Path emlPath = archiveDir.resolve(id + ".eml");
                        client.downloadBlob(blobId, emlPath);

                        List<String> mailboxes = new ArrayList<>();
                        if (email.has("mailboxIds")) {
                            email.get("mailboxIds").fieldNames().forEachRemaining(mailboxes::add);
                        }
                        String mailboxIdsStr = String.join(",", mailboxes);

                        db.saveArchived(id, blobId, mailboxIdsStr, emlPath.toString());
                        System.out.println("Archived message: " + id);
                    }
                }
            }

            position += idsNode.size();

            JsonNode totalNode = queryResponse.get("total");
            if (totalNode != null && !totalNode.isNull()) {
                System.out.printf("Progress: %d / %d messages processed%n", position, totalNode.asInt());
            } else {
                System.out.printf("Progress: %d messages processed so far%n", position);
            }

            if (idsNode.size() < limit) {
                hasMore = false;
            }
        }
        System.out.println("Local email archival complete.");
    }
}