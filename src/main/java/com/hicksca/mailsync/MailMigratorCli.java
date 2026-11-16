package com.hicksca.mailsync;

import com.hicksca.mailsync.fastmail.FastmailJmapClient;
import com.hicksca.mailsync.fastmail.FastmailSyncEngine;
import com.hicksca.mailsync.gmail.GmailAuthFactory;
import com.hicksca.mailsync.gmail.GmailSyncEngine;
import com.hicksca.mailsync.storage.MigrationDb;
import com.google.api.services.gmail.Gmail;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

public class MailMigratorCli {
    public static void main(String[] args) {
        try {
            String token = System.getenv("FASTMAIL_API_TOKEN");
            if (token == null || token.isBlank()) {
                System.err.println("FASTMAIL_API_TOKEN is required.");
                System.exit(1);
            }

            Path storageDir = Paths.get("mail_archive");
            MigrationDb db = new MigrationDb(storageDir.resolve("migration.db"));

            // 1. Fastmail Setup
            FastmailJmapClient jmap = new FastmailJmapClient(token);
            jmap.discoverSession();
            FastmailSyncEngine fastmailEngine = new FastmailSyncEngine(jmap, db, storageDir.resolve("eml"));

            // 2. Fetch mailboxes (Keep this uncommented as GmailSyncEngine needs the mailboxMap)
            Map<String, String> mailboxMap = fastmailEngine.getMailboxMap();

            // Commenting out the archive download so it doesn't slow down the script
            // fastmailEngine.runArchive();

            // 3. Gmail Setup
            Gmail gmail = GmailAuthFactory.createGmailService();
            GmailSyncEngine gmailEngine = new GmailSyncEngine(gmail, db, mailboxMap);

            // Commenting out the upload loop
            // gmailEngine.runUpload();

            // 4. Run Legacy Cleanup
            System.out.println("Starting legacy email organization (2017-2025)...");
            gmailEngine.organizeLegacyEmails(2017, 2025);
            System.out.println("Legacy email organization complete!");

        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }
}