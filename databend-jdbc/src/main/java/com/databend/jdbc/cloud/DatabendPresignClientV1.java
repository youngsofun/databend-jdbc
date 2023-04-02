package com.databend.jdbc.cloud;

import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSink;
import okio.Okio;
import okio.Source;
import org.checkerframework.checker.nullness.qual.NonNull;

import javax.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.time.Duration;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

public class DatabendPresignClientV1 implements DatabendPresignClient {

    private static final int MaxRetryAttempts = 5;

    private static final Duration RetryTimeout = Duration.ofMinutes(5);
    private final OkHttpClient client;
    private final String uri;

    public DatabendPresignClientV1(OkHttpClient client, String uri) {
        Logger.getLogger(OkHttpClient.class.getName()).setLevel(Level.FINEST);
        this.client = client;
        this.uri = uri;
    }

    private void uploadFromStream(InputStream inputStream, String stageName, String relativePath, String name, long fileSize) throws IOException {
        // multipart upload input stream into /v1/upload_to_stage
        RequestBody requestBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("upload", name, new InputStreamRequestBody(null, inputStream, fileSize))
                .build();
        Headers headers = new Headers.Builder()
                .add("stage_name", stageName)
                .add("relative_path", relativePath)
                .build();

        HttpUrl url = HttpUrl.get(this.uri);
        url = new HttpUrl.Builder()
                .scheme(url.scheme())
                .host(url.host())
                .port(url.port())
                .encodedPath("/v1/upload_to_stage")
                .build();
        Request request = new Request.Builder()
                .url(url)
                .headers(headers)
                .put(requestBody)
                .build();
        try {
            executeInternal(request, true);

        } catch (IOException e) {
            throw new IOException("uploadFromStreamAPI failed", e);
        }

    }

    private void uploadFromStream(InputStream inputStream, Headers headers, String presignedUrl, long fileSize) throws IOException {
        requireNonNull(inputStream, "inputStream is null");
        Request r = putRequest(headers, presignedUrl, inputStream, fileSize);
        try {
            executeInternal(r, true);
        } catch (IOException e) {
            throw new IOException("uploadFromStream failed", e);
        } finally {

        }
    }

    private ResponseBody executeInternal(Request request, boolean shouldClose) throws IOException {
        requireNonNull(request, "request is null");
        long start = System.nanoTime();
        long attempts = 0;
        Exception cause = null;
        while (true) {
            if (attempts > 0) {
                Duration sinceStart = Duration.ofNanos(System.nanoTime() - start);
                if (sinceStart.getSeconds() >= 30) {
                    System.out.println("Presign failed" + cause.toString());
                    throw new RuntimeException(format("Error execute presign (attempts: %s, duration: %s)", attempts, sinceStart), cause);
                }
                if (attempts >= MaxRetryAttempts) {
                    System.out.println("Presign failed" + cause.toString());
                    throw new RuntimeException(format("Error execute presign (attempts: %s, duration: %s)", attempts, sinceStart), cause);
                }

                try {
                    MILLISECONDS.sleep(attempts * 100);
                } catch (InterruptedException e) {
                    try {
                    } finally {
                        Thread.currentThread().interrupt();
                    }
                    throw new RuntimeException("StatementClient thread was interrupted");
                }
            }
            attempts++;
            Response response = null;
            try {
                response = client.newCall(request).execute();
                if (response.isSuccessful()) {
                    return response.body();
                } else if (response.code() == 401) {

                    throw new RuntimeException("Error exeucte presign, Unauthorized user: " + response.code() + " " + response.message());
                } else if (response.code() >= 503) {
                    cause = new RuntimeException("Error execute presign, service unavailable: " + response.code() + " " + response.message());
                    continue;
                } else if (response.code() >= 400) {
                    cause = new RuntimeException("Error execute presign, configuration error: " + response.code() + " " + response.message());
                    continue;
                }
            } catch (RuntimeException e) {
                cause = e;
                continue;
            } finally {
                if (shouldClose) {
                    try {
                        if (response != null) {
                            response.close();
                        }
                    } catch (Exception e) {
                        // ignore
                    }
                }
            }

        }
    }

    @Override
    public void presignUpload(File srcFile, InputStream inputStream, Headers headers,
                              String presignedUrl, long fileSize, boolean uploadFromStream) throws IOException {

        InputStream it = null;
        if (!uploadFromStream) {
            it = Files.newInputStream(srcFile.toPath());
        } else {
            it = inputStream;
        }
        uploadFromStream(it, headers, presignedUrl, fileSize);
    }

    @Override
    public void presignUpload(File srcFile, InputStream inputStream, String stageName, String relativePath, String name, long fileSize, boolean uploadFromStream) throws IOException {
        if (!uploadFromStream) {
            try (InputStream it = Files.newInputStream(srcFile.toPath())) {
                uploadFromStream(it, stageName, relativePath, name, fileSize);
            }
        } else {
            uploadFromStream(inputStream, stageName, relativePath, name, fileSize);
        }
    }

    @Override
    public void presignDownload(String destFileName, Headers headers, String presignedUrl) {
        Request r = getRequest(headers, presignedUrl);
        try (ResponseBody body = executeInternal(r, false)) {
            BufferedSink sink = Okio.buffer(Okio.sink(new File(destFileName)));
            sink.writeAll(body.source());
            sink.close();
        } catch (IOException e) {
            throw new RuntimeException("presignDownload failed", e);
        }
    }

    @Override
    public InputStream presignDownloadStream(Headers headers, String presignedUrl) {
        Request r = getRequest(headers, presignedUrl);
        try {
            ResponseBody responseBody = executeInternal(r, false);
            return responseBody.byteStream();
        } catch (IOException e) {
            throw new RuntimeException("presignDownloadStream failed", e);
        }
    }

    private Request getRequest(Headers headers, String url) {
        return new Request.Builder().headers(headers).url(url).get().build();
    }

    private Request putRequest(Headers headers, String url, InputStream inputStream, long fileSize)
            throws IOException {
        RequestBody input = new InputStreamRequestBody(null, inputStream, fileSize);
        return new Request.Builder().headers(headers).url(url).put(input).build();
    }

}

class InputStreamRequestBody extends RequestBody {
    private final InputStream inputStream;
    private final MediaType contentType;
    private final long fileSize;

    public InputStreamRequestBody(MediaType contentType, InputStream inputStream, long fileSize) {
        if (inputStream == null) throw new NullPointerException("inputStream == null");
        this.contentType = contentType;
        this.inputStream = inputStream;
        this.fileSize = fileSize;
    }

    @Nullable
    @Override
    public MediaType contentType() {
        return contentType;
    }

    @Override
    public long contentLength() throws IOException {
        return inputStream.available() == 0 ? -1 : inputStream.available();
    }

    @Override
    public void writeTo(@NonNull BufferedSink sink) throws IOException {

        try (Source source = Okio.source(inputStream)) {
            sink.writeAll(source);
        } catch (IOException e) {
            throw new IOException("writeTo failed", e);
        }
    }
}
