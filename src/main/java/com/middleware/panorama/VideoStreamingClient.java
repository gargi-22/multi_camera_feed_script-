package com.middleware.panorama;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;

/**
 * Streaming video download client with chunked range-request support.
 *
 * Optimisations over the original version:
 *   - HttpClient is built once and reused (connection pooling)
 *   - Buffer is allocated once outside the download loop
 *   - Content-type validation extracted to a reusable method
 *   - Cleaner resource handling via try-with-resources
 */
public class VideoStreamingClient {

    static final int DEFAULT_CHUNK_SIZE = 256 * 1024;
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

    public static void main(String[] args) throws IOException, InterruptedException {
        if (args.length < 2) {
            System.out.println("Usage: java VideoStreamingClient <video-url> <output-file> [chunk-size-bytes]");
            System.out.println("Example: java VideoStreamingClient http://localhost:9090/stitch downloaded.mjpeg");
            return;
        }

        URI videoUri = URI.create(args[0]);
        Path outputFile = Paths.get(args[1]);
        int chunkSize = args.length > 2 ? Integer.parseInt(args[2]) : DEFAULT_CHUNK_SIZE;

        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunk-size-bytes must be positive");
        }

        if (Files.exists(outputFile)) {
            System.out.println("Output file already exists. Overwriting: " + outputFile);
        }

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();

        long fileSize = fetchContentLength(client, videoUri);
        if (fileSize <= 0) {
            System.out.println("Could not determine content length. Falling back to single-stream download.");
            downloadSingleStream(client, videoUri, outputFile);
            return;
        }

        System.out.println("Streaming video from: " + videoUri);
        System.out.println("Target file: " + outputFile);
        System.out.println("Total size: " + fileSize + " bytes");
        System.out.println("Chunk size: " + chunkSize + " bytes\n");

        byte[] buffer = new byte[8192];

        try (FileOutputStream fos = new FileOutputStream(outputFile.toFile())) {
            long downloaded = 0;
            while (downloaded < fileSize) {
                long end = Math.min(downloaded + chunkSize - 1, fileSize - 1);
                HttpRequest chunkRequest = HttpRequest.newBuilder()
                        .uri(videoUri)
                        .timeout(REQUEST_TIMEOUT)
                        .header("Range", "bytes=" + downloaded + "-" + end)
                        .GET()
                        .build();

                HttpResponse<InputStream> response = client.send(chunkRequest, BodyHandlers.ofInputStream());
                validateNotHtml(response, videoUri);

                int status = response.statusCode();
                if (status != 206 && status != 200) {
                    throw new IOException("Unexpected server response: " + status);
                }

                try (InputStream input = response.body()) {
                    int bytesRead;
                    while ((bytesRead = input.read(buffer)) != -1) {
                        fos.write(buffer, 0, bytesRead);
                    }
                }

                downloaded = end + 1;
                printProgress(downloaded, fileSize);
            }
        }

        System.out.println("\nDownload complete.");
    }

    static long fetchContentLength(HttpClient client, URI uri)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(REQUEST_TIMEOUT)
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
        validateNotHtml(response, uri);

        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            String lengthHeader = response.headers().firstValue("Content-Length").orElse("0");
            try {
                return Long.parseLong(lengthHeader);
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return -1;
    }

    static void downloadSingleStream(HttpClient client, URI uri, Path outputFile)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();

        HttpResponse<InputStream> response = client.send(request, BodyHandlers.ofInputStream());
        validateNotHtml(response, uri);

        if (response.statusCode() != 200) {
            throw new IOException("Unexpected server response: " + response.statusCode());
        }

        byte[] buffer = new byte[8192];
        try (InputStream input = response.body();
             FileOutputStream fos = new FileOutputStream(outputFile.toFile())) {
            int bytesRead;
            long total = 0;
            while ((bytesRead = input.read(buffer)) != -1) {
                fos.write(buffer, 0, bytesRead);
                total += bytesRead;
            }
            System.out.println("Downloaded " + total + " bytes to " + outputFile);
        }
    }

    static void validateNotHtml(HttpResponse<?> response, URI uri) throws IOException {
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        if (contentType.toLowerCase().contains("text/html")) {
            throw new IOException("URL " + uri
                    + " returned HTML content, not video. Use /stitch instead of /play.");
        }
    }

    static void printProgress(long downloaded, long total) {
        int percent = (int) ((downloaded * 100) / total);
        System.out.print("\rDownloaded " + downloaded + "/" + total
                + " bytes (" + percent + "%)");
    }
}
