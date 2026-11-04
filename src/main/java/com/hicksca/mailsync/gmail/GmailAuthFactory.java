package com.hicksca.mailsync.gmail;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.GmailScopes;
import com.sun.net.httpserver.HttpServer;

import java.awt.Desktop;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class GmailAuthFactory {
    private static final GsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();
    private static final List<String> SCOPES = Collections.singletonList(GmailScopes.GMAIL_INSERT);
    private static final String TOKENS_DIRECTORY_PATH = "tokens";

    public static Gmail createGmailService() throws Exception {
        final NetHttpTransport httpTransport = GoogleNetHttpTransport.newTrustedTransport();

        InputStream in = GmailAuthFactory.class.getResourceAsStream("/credentials.json");
        if (in == null) {
            throw new IllegalStateException("credentials.json missing! Place it under src/main/resources/");
        }

        GoogleClientSecrets clientSecrets = GoogleClientSecrets.load(JSON_FACTORY, new InputStreamReader(in));

        GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
                httpTransport, JSON_FACTORY, clientSecrets, SCOPES)
                .setDataStoreFactory(new FileDataStoreFactory(new File(TOKENS_DIRECTORY_PATH)))
                .setAccessType("offline")
                .build();

        Credential credential = flow.loadCredential("user");
        if (credential == null || (credential.getRefreshToken() == null && credential.getExpiresInSeconds() <= 60)) {
            credential = authorizeWithLocalBrowser(flow);
        }

        return new Gmail.Builder(httpTransport, JSON_FACTORY, credential)
                .setApplicationName("Fastmail to Gmail Migrator")
                .build();
    }

    private static Credential authorizeWithLocalBrowser(GoogleAuthorizationCodeFlow flow) throws Exception {
        CompletableFuture<String> codeFuture = new CompletableFuture<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(8888), 0);

        server.createContext("/", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            String code = null;
            if (query != null) {
                for (String param : query.split("&")) {
                    String[] pair = param.split("=");
                    if (pair.length == 2 && pair[0].equals("code")) {
                        code = URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
                        break;
                    }
                }
            }

            String responseText = "<html><body><h2>Authentication successful!</h2><p>You can close this tab and return to IntelliJ.</p></body></html>";
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, responseText.getBytes(StandardCharsets.UTF_8).length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseText.getBytes(StandardCharsets.UTF_8));
            }

            if (code != null) {
                codeFuture.complete(code);
            }
        });

        server.start();

        String redirectUri = "http://localhost:8888/";
        String authorizationUrl = flow.newAuthorizationUrl().setRedirectUri(redirectUri).build();

        System.out.println("Opening browser for Google authorization: " + authorizationUrl);
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(new URI(authorizationUrl));
        } else {
            System.out.println("Please open the URL above manually in your browser.");
        }

        String authCode = codeFuture.get();
        server.stop(1);

        var tokenResponse = flow.newTokenRequest(authCode).setRedirectUri(redirectUri).execute();
        return flow.createAndStoreCredential(tokenResponse, "user");
    }
}