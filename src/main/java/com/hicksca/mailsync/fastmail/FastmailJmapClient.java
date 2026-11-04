package com.hicksca.mailsync.fastmail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public class FastmailJmapClient {
    private final String apiToken;
    private final OkHttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    private String apiUrl;
    private String downloadUrl;
    private String accountId;

    public FastmailJmapClient(String apiToken) {
        this.apiToken = apiToken;
        // Increase timeouts to prevent dropping on 20MB+ attachments or slow connections
        this.http = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(60))
                .readTimeout(Duration.ofMinutes(3))
                .writeTimeout(Duration.ofMinutes(3))
                .retryOnConnectionFailure(true)
                .build();
    }

    public void discoverSession() throws IOException {
        Request request = new Request.Builder()
                .url("https://api.fastmail.com/jmap/session")
                .header("Authorization", "Bearer " + apiToken)
                .build();

        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Fastmail Session Error (" + response.code() + "): " + response.body().string());
            }
            JsonNode root = mapper.readTree(response.body().string());
            this.apiUrl = root.get("apiUrl").asText();
            this.downloadUrl = root.get("downloadUrl").asText();
            this.accountId = root.get("primaryAccounts").get("urn:ietf:params:jmap:mail").asText();
        }
    }

    public JsonNode executeCall(String method, Map<String, Object> arguments) throws IOException {
        Map<String, Object> payload = Map.of(
                "using", List.of("urn:ietf:params:jmap:core", "urn:ietf:params:jmap:mail"),
                "methodCalls", List.of(List.of(method, arguments, "call-1"))
        );

        RequestBody body = RequestBody.create(
                mapper.writeValueAsString(payload),
                MediaType.parse("application/json; charset=utf-8")
        );

        Request request = new Request.Builder()
                .url(apiUrl)
                .header("Authorization", "Bearer " + apiToken)
                .post(body)
                .build();

        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("JMAP Call Failed: " + response.code());
            }
            JsonNode tree = mapper.readTree(response.body().string());
            return tree.get("methodResponses").get(0).get(1);
        }
    }

    public void downloadBlob(String blobId, Path destination) throws IOException {
        String url = downloadUrl
                .replace("{accountId}", accountId)
                .replace("{blobId}", blobId)
                .replace("{type}", "application/octet-stream")
                .replace("{name}", blobId + ".eml");

        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + apiToken)
                .build();

        int maxAttempts = 4;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try (Response response = http.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new IOException("Blob download error: HTTP " + response.code());
                }
                try (InputStream in = response.body().byteStream()) {
                    Files.copy(in, destination, StandardCopyOption.REPLACE_EXISTING);
                }
                return; // Successful download
            } catch (IOException e) {
                if (attempt == maxAttempts) {
                    throw e; // Exceeded retries, fail
                }
                System.err.printf("Download transient error for %s (attempt %d/%d): %s. Retrying in %ds...%n",
                        blobId, attempt, maxAttempts, e.getMessage(), attempt * 3);
                try {
                    Thread.sleep(attempt * 3000L);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted during retry backoff", e);
                }
            }
        }
    }

    public String getAccountId() {
        return accountId;
    }
}