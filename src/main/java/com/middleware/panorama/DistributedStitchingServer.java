package com.middleware.panorama;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;
import org.opencv.videoio.VideoCapture;
import org.opencv.videoio.VideoWriter;

/**
 * Distributed panoramic stitching server.
 *
 * Reads MJPEG streams from remote camera nodes (each running
 * {@link CameraStreamingServer}), stitches them into a panorama,
 * records the output as an MP4 file, and serves a live MJPEG preview.
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

        VideoCapture[] captures = openStreams(urls);
        if (captures == null) return;

        System.out.println("Connected to " + captures.length + " remote stream(s).");

        int overlap = StitchingCore.clampOverlap(OVERLAP_PX, TARGET_WIDTH);
        int panoW = StitchingCore.panoramaWidth(captures.length, TARGET_WIDTH, overlap);

        int fourcc = VideoWriter.fourcc('m', 'p', '4', 'v');
        VideoWriter writer = new VideoWriter(outputPath, fourcc, FPS,
                new Size(panoW, TARGET_HEIGHT));
        if (!writer.isOpened()) {
            System.err.println("Cannot create MP4 output file: " + outputPath);
            releaseCaptures(captures);
            return;
        }
        System.out.println("Recording to: " + outputPath);

        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        } catch (BindException e) {
            System.err.println("Port " + port + " is already in use. "
                    + "Please stop the other process or choose a different port.");
            writer.release();
            releaseCaptures(captures);
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

        Thread captureThread = new Thread(
                () -> captureLoop(captures, writer), "capture-stitch");
        captureThread.setDaemon(true);
        captureThread.start();

        System.out.println("Server started on port " + port);
        System.out.println("Live stream  \u2192  http://localhost:" + port + "/play");
        System.out.println("MJPEG stream \u2192  http://localhost:" + port + "/stitch");
        System.out.println("Metadata     \u2192  http://localhost:" + port + "/meta");
        System.out.println("MP4 output   \u2192  " + outputPath + " (finalized on shutdown)");
        printNetworkAddresses(port);

        HttpServer finalServer = server;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nShutting down \u2014 finalizing MP4...");
            captureThread.interrupt();
            writer.release();
            releaseCaptures(captures);
            finalServer.stop(1);
            System.out.println("MP4 saved to: " + outputPath);
        }));
    }

    // -----------------------------------------------------------------
    //  Background capture, stitch, and record loop
    // -----------------------------------------------------------------

    static void captureLoop(VideoCapture[] captures, VideoWriter writer) {
        StitchingCore.StitchWorkspace ws =
                new StitchingCore.StitchWorkspace(TARGET_HEIGHT, TARGET_WIDTH, OVERLAP_PX);

        Mat[] frames  = new Mat[captures.length];
        Mat[] resized = new Mat[captures.length];
        for (int i = 0; i < captures.length; i++) {
            frames[i]  = new Mat();
            resized[i] = new Mat();
        }

        int failCount = 0;

        while (!Thread.currentThread().isInterrupted()) {
            boolean ok = readAndResize(captures, frames, resized);
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

            synchronized (writer) {
                writer.write(panorama);
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

    static boolean readAndResize(VideoCapture[] captures, Mat[] frames, Mat[] resized) {
        synchronized (captures) {
            for (int i = 0; i < captures.length; i++) {
                if (!captures[i].read(frames[i]) || frames[i].empty()) {
                    return false;
                }
                Imgproc.resize(frames[i], resized[i],
                        new Size(TARGET_WIDTH, TARGET_HEIGHT));
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

    static VideoCapture[] openStreams(String[] urls) {
        VideoCapture[] caps = new VideoCapture[urls.length];
        for (int i = 0; i < urls.length; i++) {
            System.out.println("Connecting to: " + urls[i]);
            caps[i] = new VideoCapture(urls[i]);
            if (!caps[i].isOpened()) {
                System.err.println("Cannot open remote stream: " + urls[i]);
                System.err.println("Make sure the camera node is running and accessible.");
                for (int j = 0; j <= i; j++) caps[j].release();
                return null;
            }
            System.out.println("Connected: " + urls[i]);
        }
        return caps;
    }

    static void releaseCaptures(VideoCapture[] caps) {
        for (VideoCapture c : caps) {
            if (c != null) c.release();
        }
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
