package com.hicksca.mailsync.gmail;

import com.google.api.client.googleapis.json.GoogleJsonError;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.HttpHeaders;
import com.google.api.client.http.HttpResponseException;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.ListLabelsResponse;
import com.hicksca.mailsync.storage.MigrationDb;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;

import static org.mockito.Mockito.*;

public class GmailSyncEngineTest {

    @Test
    public void testUploadSkipsOn400BadRequest() throws Exception {
        // 1. Manually mock the API tree
        Gmail gmail = mock(Gmail.class);
        Gmail.Users users = mock(Gmail.Users.class);
        Gmail.Users.Labels labels = mock(Gmail.Users.Labels.class);
        Gmail.Users.Labels.List labelsList = mock(Gmail.Users.Labels.List.class);
        Gmail.Users.Messages messages = mock(Gmail.Users.Messages.class);

        // Use RETURNS_SELF so the fluent builder methods (setNeverMarkSpam) never return null
        Gmail.Users.Messages.GmailImport importRequest = mock(Gmail.Users.Messages.GmailImport.class, RETURNS_SELF);

        when(gmail.users()).thenReturn(users);
        when(users.labels()).thenReturn(labels);
        when(users.messages()).thenReturn(messages);
        when(labels.list(anyString())).thenReturn(labelsList);
        when(labelsList.execute()).thenReturn(new ListLabelsResponse().setLabels(Collections.emptyList()));

        // 2. Mock Database using the real file path for ALL strings to guarantee file.exists() passes
        MigrationDb db = mock(MigrationDb.class);
        File tempFile = Files.createTempFile("test-email", ".eml").toFile();
        tempFile.deleteOnExit();
        String path = tempFile.getAbsolutePath(); // Use path for all string fields

        MigrationDb.MessageRecord record = new MigrationDb.MessageRecord(path, path, path, path, 0);
        when(db.getPendingUploads()).thenReturn(List.of(record));

        // 3. Build synthetic 400 error
        HttpResponseException.Builder builder = new HttpResponseException.Builder(400, "Bad Request", new HttpHeaders());
        GoogleJsonError error = new GoogleJsonError();
        error.setCode(400);
        GoogleJsonResponseException fake400Error = new GoogleJsonResponseException(builder, error);

        // 4. Connect the import request and set execute() to throw the error
        when(messages.gmailImport(any(), any(), any())).thenReturn(importRequest);
        when(importRequest.execute()).thenThrow(fake400Error);

        // 5. Run upload
        GmailSyncEngine engine = new GmailSyncEngine(gmail, db, Collections.emptyMap());
        engine.runUpload();

        // 6. Verify skip was logged (using the 'path' variable since we assigned it to the ID field)
        verify(db).markUploaded(path, "SKIPPED_400_BAD_REQUEST");
    }
}