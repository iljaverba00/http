package com.getcapacitor.plugin.http;

import static com.getcapacitor.plugin.http.MimeType.APPLICATION_JSON;
import static com.getcapacitor.plugin.http.MimeType.APPLICATION_VND_API_JSON;

import android.content.Context;
import android.text.TextUtils;
import android.util.Base64;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PluginCall;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class HttpRequestHandler {

    /**
     * An enum specifying conventional HTTP Response Types
     * See https://developer.mozilla.org/en-US/docs/Web/API/XMLHttpRequest/responseType
     */
    public enum ResponseType {
        ARRAY_BUFFER("arraybuffer"),
        BLOB("blob"),
        DOCUMENT("document"),
        JSON("json"),
        TEXT("text");

        private final String name;

        ResponseType(String name) {
            this.name = name;
        }

        static final ResponseType DEFAULT = TEXT;

        static ResponseType parse(String value) {
            for (ResponseType responseType : values()) {
                if (responseType.name.equalsIgnoreCase(value)) {
                    return responseType;
                }
            }
            return DEFAULT;
        }
    }

    /**
     * Internal builder class for building a CapacitorHttpUrlConnection
     */
    private static class HttpURLConnectionBuilder {

        private Integer connectTimeout;
        private Integer readTimeout;
        private Boolean disableRedirects;
        private JSObject headers;
        private String method;
        private URL url;

        private CapacitorHttpUrlConnection connection;

        public HttpURLConnectionBuilder setConnectTimeout(Integer connectTimeout) {
            this.connectTimeout = connectTimeout;
            return this;
        }

        public HttpURLConnectionBuilder setReadTimeout(Integer readTimeout) {
            this.readTimeout = readTimeout;
            return this;
        }

        public HttpURLConnectionBuilder setDisableRedirects(Boolean disableRedirects) {
            this.disableRedirects = disableRedirects;
            return this;
        }

        public HttpURLConnectionBuilder setHeaders(JSObject headers) {
            this.headers = headers;
            return this;
        }

        public HttpURLConnectionBuilder setMethod(String method) {
            this.method = method;
            return this;
        }

        public HttpURLConnectionBuilder setUrl(URL url) {
            this.url = url;
            return this;
        }

        public HttpURLConnectionBuilder openConnection() throws IOException {
            connection = new CapacitorHttpUrlConnection((HttpURLConnection) url.openConnection());

            connection.setAllowUserInteraction(false);
            connection.setRequestMethod(method);

            if (connectTimeout != null) connection.setConnectTimeout(connectTimeout);
            if (readTimeout != null) connection.setReadTimeout(readTimeout);
            if (disableRedirects != null) connection.setDisableRedirects(disableRedirects);

            connection.setRequestHeaders(headers);
            return this;
        }

        public HttpURLConnectionBuilder setUrlParams(JSObject params) throws MalformedURLException, URISyntaxException, JSONException {
            return this.setUrlParams(params, true);
        }

        public HttpURLConnectionBuilder setUrlParams(JSObject params, boolean shouldEncode)
            throws URISyntaxException, MalformedURLException {
            String initialQuery = url.getQuery();
            String initialQueryBuilderStr = initialQuery == null ? "" : initialQuery;

            Iterator<String> keys = params.keys();

            if (!keys.hasNext()) {
                return this;
            }

            StringBuilder urlQueryBuilder = new StringBuilder(initialQueryBuilderStr);

            // Build the new query string
            while (keys.hasNext()) {
                String key = keys.next();

                // Attempt as JSONArray and fallback to string if it fails
                try {
                    StringBuilder value = new StringBuilder();
                    JSONArray arr = params.getJSONArray(key);
                    for (int x = 0; x < arr.length(); x++) {
                        value.append(key).append("=").append(arr.getString(x));
                        if (x != arr.length() - 1) {
                            value.append("&");
                        }
                    }
                    if (urlQueryBuilder.length() > 0) {
                        urlQueryBuilder.append("&");
                    }
                    urlQueryBuilder.append(value);
                } catch (JSONException e) {
                    if (urlQueryBuilder.length() > 0) {
                        urlQueryBuilder.append("&");
                    }
                    urlQueryBuilder.append(key).append("=").append(params.getString(key));
                }
            }

            String urlQuery = urlQueryBuilder.toString();

            URI uri = url.toURI();
            if (shouldEncode) {
                URI encodedUri = new URI(uri.getScheme(), uri.getAuthority(), uri.getPath(), urlQuery, uri.getFragment());
                this.url = encodedUri.toURL();
            } else {
                String unEncodedUrlString = uri.getScheme() + "://" + uri.getAuthority() + uri.getPath() + ((!urlQuery.equals("")) ? "?" + urlQuery : "") + ((uri.getFragment() != null) ? uri.getFragment() : "");
                this.url = new URL(unEncodedUrlString);
            }

            return this;
        }

        public CapacitorHttpUrlConnection build() {
            return connection;
        }
    }

    /**
     * Builds an HTTP Response given CapacitorHttpUrlConnection and ResponseType objects.
     *   Defaults to ResponseType.DEFAULT
     * @param connection The CapacitorHttpUrlConnection to respond with
     * @throws IOException Thrown if the InputStream is unable to be parsed correctly
     * @throws JSONException Thrown if the JSON is unable to be parsed
     */
    private static JSObject buildResponse(CapacitorHttpUrlConnection connection) throws IOException, JSONException {
        return buildResponse(connection, ResponseType.DEFAULT);
    }

    /**
     * Builds an HTTP Response given CapacitorHttpUrlConnection and ResponseType objects
     * @param connection The CapacitorHttpUrlConnection to respond with
     * @param responseType The requested ResponseType
     * @return A JSObject that contains the HTTPResponse to return to the browser
     * @throws IOException Thrown if the InputStream is unable to be parsed correctly
     * @throws JSONException Thrown if the JSON is unable to be parsed
     */
    private static JSObject buildResponse(CapacitorHttpUrlConnection connection, ResponseType responseType)
        throws IOException, JSONException {
        int statusCode = connection.getResponseCode();

        JSObject output = new JSObject();
        output.put("status", statusCode);
        output.put("headers", buildResponseHeaders(connection));
        output.put("url", connection.getURL());
        output.put("data", readData(connection, responseType));

        InputStream errorStream = connection.getErrorStream();
        if (errorStream != null) {
            output.put("error", true);
        }

        return output;
    }

    /**
     * Read the existing ICapacitorHttpUrlConnection data
     * @param connection The ICapacitorHttpUrlConnection object to read in
     * @param responseType The type of HTTP response to return to the API
     * @return The parsed data from the connection
     * @throws IOException Thrown if the InputStreams cannot be properly parsed
     * @throws JSONException Thrown if the JSON is malformed when parsing as JSON
     */
    static Object readData(ICapacitorHttpUrlConnection connection, ResponseType responseType) throws IOException, JSONException {
        InputStream errorStream = connection.getErrorStream();
        String contentType = connection.getHeaderField("Content-Type");

        if (responseType != null) {
            InputStream stream = connection.getInputStream();
            switch (responseType) {
                case ARRAY_BUFFER:
                case BLOB:
                    return readStreamAsBase64(stream);
                case JSON:
                    return parseJSON(readStreamAsString(stream));
                case DOCUMENT:
                case TEXT:
                default:
                    return readStreamAsString(stream);
            }
        } else if (errorStream != null) {
            if (isOneOf(contentType, APPLICATION_JSON, APPLICATION_VND_API_JSON)) {
                return parseJSON(readStreamAsString(errorStream));
            } else {
                return readStreamAsString(errorStream);
            }
        } else if (contentType != null && contentType.contains(APPLICATION_JSON.getValue())) {
            // backward compatibility
            return parseJSON(readStreamAsString(connection.getInputStream()));
        }
        return "Set Response TYPE !!!";
    }

    /**
     * Helper function for determining if the Content-Type is a typeof an existing Mime-Type
     * @param contentType The Content-Type string to check for
     * @param mimeTypes The Mime-Type values to check against
     * @return
     */
    private static boolean isOneOf(String contentType, MimeType... mimeTypes) {
        if (contentType != null) {
            for (MimeType mimeType : mimeTypes) {
                if (contentType.contains(mimeType.getValue())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Build the JSObject response headers based on the connection header map
     * @param connection The CapacitorHttpUrlConnection connection
     * @return A JSObject of the header values from the CapacitorHttpUrlConnection
     */
    private static JSObject buildResponseHeaders(CapacitorHttpUrlConnection connection) {
        JSObject output = new JSObject();

        for (Map.Entry<String, List<String>> entry : connection.getHeaderFields().entrySet()) {
            String valuesString = TextUtils.join(", ", entry.getValue());
            output.put(entry.getKey(), valuesString);
        }

        return output;
    }

    /**
     * Returns a JSObject or a JSArray based on a string-ified input
     * @param input String-ified JSON that needs parsing
     * @return A JSObject or JSArray
     * @throws JSONException thrown if the JSON is malformed
     */
    private static Object parseJSON(String input) throws JSONException {
        JSONObject json = new JSONObject();
        try {
            if ("null".equals(input.trim())) {
                return JSONObject.NULL;
            } else if ("true".equals(input.trim())) {
                return new JSONObject().put("flag", "true");
            } else if ("false".equals(input.trim())) {
                return new JSONObject().put("flag", "false");
            } else {
                try {
                    return new JSObject(input);
                } catch (JSONException e) {
                    return new JSArray(input);
                }
            }
        } catch (JSONException e) {
            return new JSArray(input);
        }
    }

    /**
     * Returns a string based on a base64 InputStream
     * @param in The base64 InputStream to convert to a String
     * @return String value of InputStream
     * @throws IOException thrown if the InputStream is unable to be read as base64
     */
    private static String readStreamAsBase64(InputStream in) throws IOException {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int readBytes;
            while ((readBytes = in.read(buffer)) != -1) {
                out.write(buffer, 0, readBytes);
            }
            byte[] result = out.toByteArray();
            return Base64.encodeToString(result, 0, result.length, Base64.DEFAULT);
        }
    }

    /**
     * Returns a string based on an InputStream
     * @param in The InputStream to convert to a String
     * @return String value of InputStream
     * @throws IOException thrown if the InputStream is unable to be read
     */
    private static String readStreamAsString(InputStream in) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in))) {
            StringBuilder builder = new StringBuilder();
            String line = reader.readLine();
            while (line != null) {
                builder.append(line);
                line = reader.readLine();
                if (line != null) {
                    builder.append(System.getProperty("line.separator"));
                }
            }
            return builder.toString();
        }
    }

    /**
     * Makes an Http Request based on the PluginCall parameters
     * @param call The Capacitor PluginCall that contains the options need for an Http request
     * @param httpMethod The HTTP method that overrides the PluginCall HTTP method
     * @throws IOException throws an IO request when a connection can't be made
     * @throws URISyntaxException thrown when the URI is malformed
     * @throws JSONException thrown when the incoming JSON is malformed
     */
    public static JSObject request(PluginCall call, String httpMethod) throws IOException, URISyntaxException, JSONException {
        String urlString = call.getString("url", "");
        JSObject headers = call.getObject("headers");
        JSObject params = call.getObject("params");
        Integer connectTimeout = call.getInt("connectTimeout");
        Integer readTimeout = call.getInt("readTimeout");
        Boolean disableRedirects = call.getBoolean("disableRedirects");
        Boolean shouldEncode = call.getBoolean("shouldEncodeUrlParams", true);
        ResponseType responseType = ResponseType.parse(call.getString("responseType"));

        String method = httpMethod != null ? httpMethod.toUpperCase() : call.getString("method", "").toUpperCase();

        boolean isHttpMutate = method.equals("DELETE") || method.equals("PATCH") || method.equals("POST") || method.equals("PUT");

        URL url = new URL(urlString);
        HttpURLConnectionBuilder connectionBuilder = new HttpURLConnectionBuilder()
            .setUrl(url)
            .setMethod(method)
            .setHeaders(headers)
            .setUrlParams(params, shouldEncode)
            .setConnectTimeout(connectTimeout)
            .setReadTimeout(readTimeout)
            .setDisableRedirects(disableRedirects)
            .openConnection();

        CapacitorHttpUrlConnection connection = connectionBuilder.build();

        // Set HTTP body on a non GET or HEAD request
        if (isHttpMutate) {
            JSValue data = new JSValue(call, "data");
            if (data.getValue() != null) {
                connection.setDoOutput(true);
                connection.setRequestBody(call, data);
            }
        }

        connection.connect();

        return buildResponse(connection, responseType);
    }

    /**
     * Makes an Http Request to download a file based on the PluginCall parameters
     * @param call The Capacitor PluginCall that contains the options need for an Http request
     * @param context The Android Context required for writing to the filesystem
     * @param progress The emitter which notifies listeners on downloading progression
     * @throws IOException throws an IO request when a connection can't be made
     * @throws URISyntaxException thrown when the URI is malformed
     */
    public static JSObject downloadFile(PluginCall call, Context context, ProgressEmitter progress)
        throws IOException, URISyntaxException, JSONException {
        String urlString = call.getString("url");
        String method = call.getString("method", "GET").toUpperCase();
        String filePath = call.getString("filePath");
        String fileDirectory = call.getString("fileDirectory", FilesystemUtils.DIRECTORY_DOCUMENTS);
        JSObject headers = call.getObject("headers");
        JSObject params = call.getObject("params");
        Integer connectTimeout = call.getInt("connectTimeout");
        Integer readTimeout = call.getInt("readTimeout");

        final URL url = new URL(urlString);
        final File file = FilesystemUtils.getFileObject(context, filePath, fileDirectory);

        HttpURLConnectionBuilder connectionBuilder = new HttpURLConnectionBuilder()
            .setUrl(url)
            .setMethod(method)
            .setHeaders(headers)
            .setUrlParams(params)
            .setConnectTimeout(connectTimeout)
            .setReadTimeout(readTimeout)
            .openConnection();

        ICapacitorHttpUrlConnection connection = connectionBuilder.build();
        InputStream connectionInputStream = connection.getInputStream();

        FileOutputStream fileOutputStream = new FileOutputStream(file, false);

        String contentLength = connection.getHeaderField("content-length");
        int bytes = 0;
        int maxBytes = 0;

        try {
            maxBytes = contentLength != null ? Integer.parseInt(contentLength) : 0;
        } catch (NumberFormatException e) {
            maxBytes = 0;
        }

        byte[] buffer = new byte[1024];
        int len;

        while ((len = connectionInputStream.read(buffer)) > 0) {
            fileOutputStream.write(buffer, 0, len);

            bytes += len;
            progress.emit(bytes, maxBytes);
        }

        connectionInputStream.close();
        fileOutputStream.close();

        return new JSObject() {
            {
                put("path", file.getAbsolutePath());
            }
        };
    }

    /**
     * Makes an Http Request to upload a file based on the PluginCall parameters
     * @param call The Capacitor PluginCall that contains the options need for an Http request
     * @param context The Android Context required for writing to the filesystem
     * @throws IOException throws an IO request when a connection can't be made
     * @throws URISyntaxException thrown when the URI is malformed
     * @throws JSONException thrown when malformed JSON is passed into the function
     */
    public static JSObject uploadFile(PluginCall call, Context context) throws IOException, URISyntaxException, JSONException {
        String urlString = call.getString("url");
        String method = call.getString("method", "POST").toUpperCase();
        String filePath = call.getString("filePath");
        String fileDirectory = call.getString("fileDirectory", FilesystemUtils.DIRECTORY_DOCUMENTS);
        String name = call.getString("name", "file");
        Integer connectTimeout = call.getInt("connectTimeout");
        Integer readTimeout = call.getInt("readTimeout");
        JSObject headers = call.getObject("headers");
        JSObject params = call.getObject("params");
        JSObject data = call.getObject("data");
        ResponseType responseType = ResponseType.parse(call.getString("responseType"));

        URL url = new URL(urlString);

        File file = FilesystemUtils.getFileObject(context, filePath, fileDirectory);

        HttpURLConnectionBuilder connectionBuilder = new HttpURLConnectionBuilder()
            .setUrl(url)
            .setMethod(method)
            .setHeaders(headers)
            .setUrlParams(params)
            .setConnectTimeout(connectTimeout)
            .setReadTimeout(readTimeout)
            .openConnection();

        CapacitorHttpUrlConnection connection = connectionBuilder.build();
        connection.setDoOutput(true);

        FileUploader builder = new FileUploader(connection.getHttpConnection());
        builder.addFilePart(name, file, data);


        return buildResponse(connection, responseType);
    }

    @FunctionalInterface
    public interface ProgressEmitter {
        void emit(Integer bytes, Integer contentLength);
    }
}
