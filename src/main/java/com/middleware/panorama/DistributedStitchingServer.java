package com.middleware.panorama;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.BindException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.util.Enumeration;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.opencv.videoio.VideoWriter;

/**
 * Distributed panoramic stitching server.
 *
 * Reads MJPEG streams from remote camera nodes (each running
 * {@link CameraStreamingServer}), stitches them into a panorama,
 * records the output as an MP4 file, and serves a live MJPEG preview.
 *
 * Uses Java HTTP client to read MJPEG streams (no FFMPEG dependency).
 *
 * Usage:
 *   java DistributedStitchingServer &lt;output.mp4&gt; &lt;url1&gt; [url2] ...
 *   java DistributedStitchingServer &lt;port&gt; &lt;output.mp4&gt; &lt;url1&gt; [url2] ...
 *
 * Examples:
 *   java DistributedStitchingServer panorama.mp4 http://192.168.1.10:9091/stitch http://192.168.1.11:9091/stitch
 *   java DistributedStitchingServer 8080 panorama.mp4 http://192.168.1.10:9091/stitch http://192.168.1.11:9091/stitch
 */
public class DistributedStitchingServer {

    static final int DEFAULT_PORT    = 9090;
    static final int TARGET_HEIGHT   = 360;
    static final int TARGET_WIDTH    = 640;
    static final int OVERLAP_PX      = 80;
    static final int JPEG_QUALITY    = 88;
    static final long FRAME_DELAY_MS = 33;
    static final double FPS          = 30.0;

    private static final AtomicReference<byte[]> latestJpeg = new AtomicReference<>();
    private static final Object frameSignal = new Object();

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            printUsage();
            return;
        }

        int port = DEFAULT_PORT;
        int argOffset = 0;

        try {
            int possiblePort = Integer.parseInt(args[0]);
            if (possiblePort >= 0 && possiblePort <= 65535 && args.length >= 3) {
                port = possiblePort;
                argOffset = 1;
            }
        } catch (NumberFormatException ignored) {
        }

        if (args.length - argOffset < 2) {
            printUsage();
            return;
        }

        String outputPath = args[argOffset];
        String[] urls = new String[args.length - argOffset - 1];
        System.arraycopy(args, argOffset + 1, urls, 0, urls.length);

        try {
            nu.pattern.OpenCV.loadLocally();
        } catch (Exception e) {
            try {
                System.loadLibrary(Core.NATIVE_LIBRARY_NAME);
            } catch (UnsatisfiedLinkError err) {
                System.err.println("OpenCV native library not found: " + err.getMessage());
                return;
            }
        }

        MjpegStreamReader[] readers = openStreams(urls);
        if (readers == null) return;

        System.out.println("Connected to " + readers.length + " remote stream(s).");

        int overlap = StitchingCore.clampOverlap(OVERLAP_PX, TARGET_WIDTH);
        int panoW = StitchingCore.panoramaWidth(readers.length, TARGET_WIDTH, overlap);

        VideoWriter writer = openVideoWriter(outputPath, panoW, TARGET_HEIGHT);
        if (writer == null) {
            System.out.println("WARNING: MP4 recording disabled (no codec available).");
            System.out.println("Live stitching will still work. Install ffmpeg for recording:");
            System.out.println("  Ubuntu/Debian: sudo apt-get install ffmpeg");
            System.out.println("  Windows: download from https://ffmpeg.org/download.html");
        }

        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        } catch (BindException e) {
            System.err.println("Port " + port + " is already in use. "
                    + "Please stop the other process or choose a different port.");
            if (writer != null) writer.release();
            closeReaders(readers);
            return;
        }

        String[] cameraNames = new String[urls.length];
        for (int i = 0; i < urls.length; i++) {
            cameraNames[i] = "remote" + i;
        }

        server.createContext("/stitch", new LiveViewHandler());
        server.createContext("/play", new CommonHandlers.PlayerPageHandler());
        server.createContext("/meta", new CommonHandlers.MetaHandler(
                port, cameraNames, TARGET_WIDTH, TARGET_HEIGHT, OVERLAP_PX));

        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();

        final VideoWriter finalWriter = writer;
        Thread captureThread = new Thread(
                () -> captureLoop(readers, finalWriter), "capture-stitch");
        captureThread.setDaemon(true);
        captureThread.start();

        System.out.println("Server started on port " + port);
        System.out.println("Live stream  \u2192  http://localhost:" + port + "/play");
        System.out.println("MJPEG stream \u2192  http://localhost:" + port + "/stitch");
        System.out.println("Metadata     \u2192  http://localhost:" + port + "/meta");
        if (writer != null) {
            System.out.println("MP4 output   \u2192  " + outputPath + " (finalized on shutdown)");
        }
        printNetworkAddresses(port);

        HttpServer finalServer = server;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nShutting down...");
            captureThread.interrupt();
            if (finalWriter != null) {
                finalWriter.release();
                System.out.println("MP4 saved to: " + outputPath);
            }
            closeReaders(readers);
            finalServer.stop(1);
        }));
    }

    // -----------------------------------------------------------------
    //  MJPEG Stream Reader — reads frames via Java HTTP client
    // -----------------------------------------------------------------

    static class MjpegStreamReader {
        private final String url;
        private InputStream inputStream;
        private HttpURLConnection connection;

        MjpegStreamReader(String url) {
            this.url = url;
        }

        boolean connect() {
            try {
                URL streamUrl = new URL(url);
                connection = (HttpURLConnection) streamUrl.openConnection();
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(10000);
                connection.setRequestMethod("GET");
                connection.connect();

                int code = connection.getResponseCode();
                if (code != 200) {
                    System.err.println("HTTP " + code + " from " + url);
                    return false;
                }
                inputStream = connection.getInputStream();
                return true;
            } catch (IOException e) {
                System.err.println("Cannot connect to " + url + ": " + e.getMessage());
                return false;
            }
        }

        byte[] readFrame() throws IOException {
            if (inputStream == null) return null;

            // Skip until we find the JPEG start marker (after boundary + headers)
            // The MJPEG format: --frame\r\nContent-Type: ...\r\nContent-Length: N\r\n\r\n<jpeg>\r\n
            int contentLength = -1;
            String line;

            // Read headers until empty line
            while ((line = readLine()) != null) {
                if (line.isEmpty()) break;
                if (line.startsWith("Content-Length:") || line.startsWith("content-length:")) {
                    try {
                        contentLength = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
                    } catch (NumberFormatException ignored) {}
                }
            }

            if (contentLength > 0) {
                // Read exact number of bytes
                byte[] data = new byte[contentLength];
                int offset = 0;
                while (offset < contentLength) {
                    int read = inputStream.read(data, offset, contentLength - offset);
                    if (read == -1) throw new IOException("Stream ended prematurely");
                    offset += read;
                }
                // Skip trailing \r\n
                readLine();
                return data;
            } else {
                // Fallback: scan for JPEG SOI/EOI markers
                return readFrameByMarkers();
            }
        }

        private byte[] readFrameByMarkers() throws IOException {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(65536);
            int prev = -1;
            boolean started = false;

            while (true) {
                int b = inputStream.read();
                if (b == -1) throw new IOException("Stream ended");

                if (!started) {
                    if (prev == 0xFF && b == 0xD8) {
                        started = true;
                        baos.write(0xFF);
                        baos.write(0xD8);
                    }
                    prev = b;
                    continue;
                }

                baos.write(b);
                if (prev == 0xFF && b == 0xD9) {
                    // End of JPEG
                    return baos.toByteArray();
                }
                prev = b;
            }
        }

        private String readLine() throws IOException {
            StringBuilder sb = new StringBuilder();
            int c;
            while ((c = inputStream.read()) != -1) {
                if (c == '\r') {
                    int next = inputStream.read();
                    if (next == '\n') break;
                    sb.append((char) c);
                    if (next != -1) sb.append((char) next);
                } else if (c == '\n') {
                    break;
                } else {
                    sb.append((char) c);
                }
            }
            return c == -1 && sb.length() == 0 ? null : sb.toString();
        }

        void close() {
            try {
                if (inputStream != null) inputStream.close();
            } catch (IOException ignored) {}
            if (connection != null) connection.disconnect();
        }
    }

    // -----------------------------------------------------------------
    //  Background capture, stitch, and record loop
    // -----------------------------------------------------------------

    static void captureLoop(MjpegStreamReader[] readers, VideoWriter writer) {
        StitchingCore.StitchWorkspace ws =
                new StitchingCore.StitchWorkspace(TARGET_HEIGHT, TARGET_WIDTH, OVERLAP_PX);

        Mat[] resized = new Mat[readers.length];
        for (int i = 0; i < readers.length; i++) {
            resized[i] = new Mat();
        }

        int failCount = 0;

        while (!Thread.currentThread().isInterrupted()) {
            boolean ok = readAndDecodeFrames(readers, resized);
            if (!ok) {
                failCount++;
                if (failCount > 30) {
                    System.err.println("Lost connection to remote stream(s). Stopping.");
                    break;
                }
                System.err.println("Frame read failed, retrying... (" + failCount + "/30)");
                try { Thread.sleep(1000); } catch (InterruptedException e) { break; }
                continue;
            }
            failCount = 0;

            Mat panorama = StitchingCore.featherStitchOptimised(
                    resized, TARGET_WIDTH, TARGET_HEIGHT, OVERLAP_PX, ws);

            if (writer != null) {
                synchronized (writer) {
                    writer.write(panorama);
                }
            }

            byte[] jpeg = StitchingCore.encodeJpeg(panorama, JPEG_QUALITY);
            latestJpeg.set(jpeg);
            synchronized (frameSignal) {
                frameSignal.notifyAll();
            }

            panorama.release();

            try {
                Thread.sleep(FRAME_DELAY_MS);
            } catch (InterruptedException e) {
                break;
            }
        }

        ws.release();
    }

    static boolean readAndDecodeFrames(MjpegStreamReader[] readers, Mat[] resized) {
        for (int i = 0; i < readers.length; i++) {
            try {
                byte[] jpegData = readers[i].readFrame();
                if (jpegData == null) return false;

                Mat raw = Imgcodecs.imdecode(new MatOfByte(jpegData), Imgcodecs.IMREAD_COLOR);
                if (raw.empty()) {
                    raw.release();
                    return false;
                }

                Imgproc.resize(raw, resized[i], new Size(TARGET_WIDTH, TARGET_HEIGHT));
                raw.release();
            } catch (IOException e) {
                return false;
            }
        }
        return true;
    }

    // -----------------------------------------------------------------
    //  Live MJPEG view handler (serves latest stitched frame)
    // -----------------------------------------------------------------

    static class LiveViewHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
                ex.sendResponseHeaders(405, -1);
                return;
            }

            ex.getResponseHeaders().set("Content-Type",
                    "multipart/x-mixed-replace; boundary=frame");
            ex.getResponseHeaders().set("Cache-Control", "no-cache");
            ex.sendResponseHeaders(200, 0);

            try (OutputStream out = ex.getResponseBody()) {
                byte[] lastServed = null;
                while (true) {
                    byte[] jpeg;
                    synchronized (frameSignal) {
                        while (latestJpeg.get() == null
                                || latestJpeg.get() == lastServed) {
                            frameSignal.wait(1000);
                        }
                        jpeg = latestJpeg.get();
                    }
                    if (jpeg == null) continue;
                    lastServed = jpeg;
                    StitchingCore.writeMjpegFrame(out, jpeg);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // -----------------------------------------------------------------
    //  Stream management
    // -----------------------------------------------------------------

    static MjpegStreamReader[] openStreams(String[] urls) {
        MjpegStreamReader[] readers = new MjpegStreamReader[urls.length];
        for (int i = 0; i < urls.length; i++) {
            System.out.println("Connecting to: " + urls[i]);
            readers[i] = new MjpegStreamReader(urls[i]);
            if (!readers[i].connect()) {
                System.err.println("Cannot open remote stream: " + urls[i]);
                System.err.println("Make sure the camera node is running and accessible.");
                for (int j = 0; j <= i; j++) readers[j].close();
                return null;
            }
            System.out.println("Connected: " + urls[i]);
        }
        return readers;
    }

    static void closeReaders(MjpegStreamReader[] readers) {
        for (MjpegStreamReader r : readers) {
            if (r != null) r.close();
        }
    }

    // -----------------------------------------------------------------
    //  Video writer with codec fallback
    // -----------------------------------------------------------------

    static VideoWriter openVideoWriter(String outputPath, int width, int height) {
        int[][] codecs = {
            {'m', 'p', '4', 'v'},
            {'a', 'v', 'c', '1'},
            {'X', 'V', 'I', 'D'},
            {'M', 'J', 'P', 'G'},
        };
        String[] codecNames = {"mp4v", "avc1", "XVID", "MJPG"};

        // For MJPG fallback, use .avi extension
        String baseName = outputPath.replaceAll("\\.[^.]+$", "");

        for (int i = 0; i < codecs.length; i++) {
            int fourcc = VideoWriter.fourcc(
                    (char) codecs[i][0], (char) codecs[i][1],
                    (char) codecs[i][2], (char) codecs[i][3]);
            String path = (i == 3) ? baseName + ".avi" : outputPath;
            VideoWriter writer = new VideoWriter(path, fourcc, FPS, new Size(width, height));
            if (writer.isOpened()) {
                System.out.println("Recording to: " + path + " (codec: " + codecNames[i] + ")");
                return writer;
            }
            writer.release();
            System.out.println("Codec " + codecNames[i] + " not available, trying next...");
        }

        System.err.println("Cannot create video output file. No suitable codec found.");
        System.err.println("Try installing ffmpeg: sudo apt-get install ffmpeg");
        return null;
    }

    // -----------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------

    static void printUsage() {
        System.err.println("Usage:");
        System.err.println("  DistributedStitchingServer <output.mp4> <url1> [url2] ...");
        System.err.println("  DistributedStitchingServer <port> <output.mp4> <url1> [url2] ...");
        System.err.println();
        System.err.println("Examples:");
        System.err.println("  DistributedStitchingServer panorama.mp4 "
                + "http://192.168.1.10:9091/stitch http://192.168.1.11:9091/stitch");
        System.err.println("  DistributedStitchingServer 8080 panorama.mp4 "
                + "http://192.168.1.10:9091/stitch http://192.168.1.11:9091/stitch");
    }

    static void printNetworkAddresses(int port) {
        try {
            Enumeration<NetworkInterface> interfaces =
                    NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (ni.isLoopback() || !ni.isUp()) continue;
                Enumeration<InetAddress> addresses = ni.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr instanceof java.net.Inet4Address) {
                        System.out.println("Network access \u2192  http://"
                                + addr.getHostAddress() + ":" + port + "/play");
                    }
                }
            }
        } catch (Exception e) {
            // best-effort
        }
    }
}
