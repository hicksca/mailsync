package com.hicksca.mailsync.gmail;

import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.FileContent;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.Label;
import com.google.api.services.gmail.model.ListLabelsResponse;
import com.google.api.services.gmail.model.Message;
import com.hicksca.mailsync.storage.MigrationDb;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GmailSyncEngine {
    private final Gmail gmail;
    private final MigrationDb db;
    private final Map<String, String> fastmailMailboxMap;
    private final Map<String, String> gmailLabelCache = new HashMap<>();

    public GmailSyncEngine(Gmail gmail, MigrationDb db, Map<String, String> fastmailMailboxMap) {
        this.gmail = gmail;
        this.db = db;
        this.fastmailMailboxMap = fastmailMailboxMap;
    }

    private void initGmailLabels() throws IOException {
        ListLabelsResponse res = gmail.users().labels().list("me").execute();
        if (res.getLabels() != null) {
            for (Label label : res.getLabels()) {
                gmailLabelCache.put(label.getName().toLowerCase(), label.getId());
            }
        }
    }

    private String getOrCreateLabel(String labelName) throws IOException {
        String normalizedName = labelName.trim();

        if (normalizedName.isEmpty()) {
            return "INBOX";
        }

        String upper = normalizedName.toUpperCase();
        switch (upper) {
            case "INBOX": return "INBOX";
            case "SPAM":
            case "JUNK MAIL":
            case "JUNK": return "SPAM";
            case "TRASH":
            case "DELETED MESSAGES": return "TRASH";
            case "DRAFTS": return "DRAFT";
            case "SENT":
            case "SENT MESSAGES": return "SENT";
            case "STARRED": return "STARRED";
            case "UNREAD": return "UNREAD";
            case "IMPORTANT": return "IMPORTANT";
        }

        String key = labelName.toLowerCase();
        if (gmailLabelCache.containsKey(key)) {
            return gmailLabelCache.get(key);
        }

        try {
            Label newLabel = new Label()
                    .setName(normalizedName)
                    .setLabelListVisibility("labelShow")
                    .setMessageListVisibility("show");

            Label createdLabel = gmail.users().labels().create("me", newLabel).execute();
            gmailLabelCache.put(key, createdLabel.getId());
            return createdLabel.getId();

        } catch (GoogleJsonResponseException e) {
            // Label creation catch block
            if (e.getStatusCode() == 400) {
                System.err.println("WARNING: Gmail API rejected folder name: '" + labelName + "'. Routing to INBOX.");
                return "INBOX";
            }
            throw e;
        }
    }

    public void runUpload() throws IOException, SQLException, InterruptedException {
        initGmailLabels();
        List<MigrationDb.MessageRecord> pending = db.getPendingUploads();
        System.out.println("Pending uploads found in database: " + pending.size());

        for (int i = 0; i < pending.size(); i++) {
            MigrationDb.MessageRecord record = pending.get(i);
            File file = Path.of(record.localPath()).toFile();

            if (!file.exists()) {
                System.err.println("File missing, skipping: " + record.localPath());
                continue;
            }

            List<String> labelIds = new ArrayList<>();
            if (record.mailboxIds() != null && !record.mailboxIds().isBlank()) {
                for (String mbId : record.mailboxIds().split(",")) {
                    String mbName = fastmailMailboxMap.get(mbId);
                    if (mbName != null) {
                        if (mbName.equalsIgnoreCase("Inbox")) {
                            labelIds.add("INBOX");
                        } else if (mbName.equalsIgnoreCase("Trash")) {
                            labelIds.add("TRASH");
                        } else if (mbName.equalsIgnoreCase("Sent") || mbName.equalsIgnoreCase("Sent Messages")) {
                            labelIds.add("SENT");
                        } else if (mbName.equalsIgnoreCase("Archive")) {
                            // Do nothing
                        } else {
                            labelIds.add(getOrCreateLabel(mbName));
                        }
                    }
                }
            }

            Message meta = new Message().setLabelIds(labelIds);
            FileContent media = new FileContent("message/rfc822", file);

            Message inserted = null;
            int maxRetries = 3;

            for (int attempt = 1; attempt <= maxRetries; attempt++) {
                try {
                    inserted = gmail.users().messages()
                            .gmailImport("me", meta, media)
                            .setNeverMarkSpam(true)
                            .setProcessForCalendar(false)
                            .execute();
                    break; // Success, break out of the retry loop

                } catch (GoogleJsonResponseException e) {
                    // 1. Catch 400 Bad Request (blocked attachments)
                    if (e.getStatusCode() == 400) {
                        System.err.println("Skipping message due to Google API rejection (blocked attachment): " + record.fastmailId());
                        inserted = new Message().setId("SKIPPED_400_BAD_REQUEST");
                        break;
                    }
                    // 2. Catch 500 (Internal Server Error) and 429 (Rate Limit)
                    else if (e.getStatusCode() >= 500 || e.getStatusCode() == 429) {
                        if (attempt == maxRetries) {
                            throw e; // Fail permanently if we exhaust retries
                        }
                        System.err.println("Google API hiccup (HTTP " + e.getStatusCode() + "). Retrying in 5 seconds... (" + attempt + "/" + maxRetries + ")");
                        Thread.sleep(5000); // Wait 5 seconds before trying again
                    }
                    else {
                        throw e; // Bubble up any other unknown errors
                    }
                } catch (IOException e) {
                    // Catch network-level drops like java.net.SocketTimeoutException
                    if (attempt == maxRetries) {
                        throw e;
                    }
                    System.err.println("Network connection error (" + e.getMessage() + "). Retrying in 5 seconds... (" + attempt + "/" + maxRetries + ")");
                    Thread.sleep(5000);
                }
            }

            if (inserted != null) {
                db.markUploaded(record.fastmailId(), inserted.getId());
                System.out.printf("[%d/%d] Uploaded to Gmail: %s -> %s%n", i + 1, pending.size(), record.fastmailId(), inserted.getId());
            } else {
                throw new IOException("Failed to upload message after retries: " + record.fastmailId());
            }

            Thread.sleep(350);
        }

        System.out.println("Gmail migration process completed.");
    }
}