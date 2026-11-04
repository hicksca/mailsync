package com.hicksca.mailsync.gmail;

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
import java.util.*;

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
        String key = labelName.toLowerCase();
        if (gmailLabelCache.containsKey(key)) {
            return gmailLabelCache.get(key);
        }

        Label newLabel = new Label()
                .setName(labelName)
                .setLabelListVisibility("labelShow")
                .setMessageListVisibility("show");

        Label created = gmail.users().labels().create("me", newLabel).execute();
        gmailLabelCache.put(key, created.getId());
        return created.getId();
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

            // Map Fastmail mailboxes to Gmail label IDs
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
                        } else {
                            labelIds.add(getOrCreateLabel(mbName));
                        }
                    }
                }
            }

            Message meta = new Message().setLabelIds(labelIds);
            FileContent media = new FileContent("message/rfc822", file);

            // Import preserving raw RFC 822 timestamps & headers
            Message inserted = gmail.users().messages()
                    .gmailImport("me", meta, media)
                    .setNeverMarkSpam(true)
                    .setProcessForCalendar(false)
                    .execute();

            db.markUploaded(record.fastmailId(), inserted.getId());
            System.out.printf("[%d/%d] Uploaded to Gmail: %s -> %s%n", i + 1, pending.size(), record.fastmailId(), inserted.getId());

            // Rate pacing: Sleep 350ms to stay safely under Gmail API user quota (25 units per import; max 6,000 units/min per user)
            Thread.sleep(350);
        }

        System.out.println("Gmail migration process completed.");
    }
}
