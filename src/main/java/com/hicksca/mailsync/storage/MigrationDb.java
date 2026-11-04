package com.hicksca.mailsync.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class MigrationDb {
    private final String dbUrl;

    public record MessageRecord(String fastmailId, String blobId, String mailboxIds, String localPath, int isUploaded) {}

    public MigrationDb(Path dbPath) throws SQLException, IOException {
        Path parent = dbPath.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        this.dbUrl = "jdbc:sqlite:" + dbPath.toAbsolutePath();
        init();
    }

    private void init() throws SQLException {
        try (Connection conn = DriverManager.getConnection(dbUrl);
             Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS messages (
                    fastmail_id TEXT PRIMARY KEY,
                    blob_id TEXT NOT NULL,
                    mailbox_ids TEXT,
                    local_path TEXT,
                    is_archived INTEGER DEFAULT 0,
                    is_uploaded INTEGER DEFAULT 0,
                    gmail_id TEXT
                );
            """);
        }
    }

    public boolean isArchived(String fastmailId) throws SQLException {
        String sql = "SELECT 1 FROM messages WHERE fastmail_id = ? AND is_archived = 1";
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, fastmailId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public void saveArchived(String fastmailId, String blobId, String mailboxIds, String localPath) throws SQLException {
        String sql = """
            INSERT INTO messages (fastmail_id, blob_id, mailbox_ids, local_path, is_archived)
            VALUES (?, ?, ?, ?, 1)
            ON CONFLICT(fastmail_id) DO UPDATE SET
                blob_id = excluded.blob_id,
                mailbox_ids = excluded.mailbox_ids,
                local_path = excluded.local_path,
                is_archived = 1;
        """;
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, fastmailId);
            ps.setString(2, blobId);
            ps.setString(3, mailboxIds);
            ps.setString(4, localPath);
            ps.executeUpdate();
        }
    }

    public List<MessageRecord> getPendingUploads() throws SQLException {
        List<MessageRecord> records = new ArrayList<>();
        String sql = "SELECT fastmail_id, blob_id, mailbox_ids, local_path, is_uploaded FROM messages WHERE is_archived = 1 AND is_uploaded = 0";
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                records.add(new MessageRecord(
                        rs.getString("fastmail_id"),
                        rs.getString("blob_id"),
                        rs.getString("mailbox_ids"),
                        rs.getString("local_path"),
                        rs.getInt("is_uploaded")
                ));
            }
        }
        return records;
    }

    public void markUploaded(String fastmailId, String gmailId) throws SQLException {
        String sql = "UPDATE messages SET is_uploaded = 1, gmail_id = ? WHERE fastmail_id = ?";
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, gmailId);
            ps.setString(2, fastmailId);
            ps.executeUpdate();
        }
    }
}