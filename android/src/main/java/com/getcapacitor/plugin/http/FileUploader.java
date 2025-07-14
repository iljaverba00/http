package com.getcapacitor.plugin.http;

import com.getcapacitor.JSObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URLConnection;

public class FileUploader {
    private final OutputStream outputStream;

    public FileUploader(HttpURLConnection connection) throws IOException {
        connection.setRequestProperty("Content-Type", "application/octet-stream");
        outputStream = connection.getOutputStream();
    }

    public void addFilePart(String fieldName, File uploadFile, JSObject data) throws IOException {
        try (FileInputStream inputStream = new FileInputStream(uploadFile)) {
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
            outputStream.flush();
        } finally {
            outputStream.close(); // закрываем outputStream в любом случае
        }
    }
}
