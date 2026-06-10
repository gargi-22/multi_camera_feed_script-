package com.middleware.panorama;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;
import org.opencv.videoio.VideoCapture;

/**
 * Live-camera panoramic stitching server.
 *
 * Opens N system cameras (by device index), captures frames, resizes them,
 * feeds them into the feather-blend stitching algorithm, and serves the
 * result as an MJPEG stream.
 *
 * Usage:
 *   java CameraStreamingServer [port] [camera_indices...]
 *
 * Examples:
 *   java CameraStreamingServer                  → port 9091, cameras 0,1,2,3
 *   java CameraStreamingServer 8080 0 1         → port 8080, cameras 0,1
 *   java CameraStreamingServer 9091 0 2 4 6     → port 9091, cameras 0,2,4,6
 */
public class CameraStreamingServer {

    static final int DEFAULT_PORT   = 9091;
    static final int TARGET_HEIGHT  = 360;
    static final int TARGET_WIDTH   = 640;
    static final int OVERLAP_PX     = 80;
    static final int JPEG_QUALITY   = 88;
    static final long FRAME_DELAY_MS = 33;  // ~30 fps cap

    public static void main(String[] args) throws IOException {
        int port = DEFAULT_PORT;
        int[] cameraIndices = {0, 1, 2, 3};

        if (args.length >= 1) {
            port = Integer.parseInt(args[0]);
        }
        if (args.length >= 2) {
            cameraIndices = new int[args.length - 1];
            for (int i = 1; i < args.length; i++) {
                cameraIndices[i - 1] = Integer.parseInt(args[i]);
            }
        }

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

        String[] cameraNames = buildCameraNames(cameraIndices);
        VideoCapture[] captures = openCameras(cameraIndices);
        if (captures == null) return;

        System.out.println("Opened " + captures.length + " camera(s): indices "
                + formatIndices(cameraIndices));

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/stitch", new LiveStitchHandler(captures));
        server.createContext("/play", new CommonHandlers.PlayerPageHandler());
        server.createContext("/meta", new CommonHandlers.MetaHandler(
                port, cameraNames, TARGET_WIDTH, TARGET_HEIGHT, OVERLAP_PX));
        server.createContext("/snapshot", new SnapshotHandler(captures));

        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();

        System.out.println("Live stream     →  http://localhost:" + port + "/play");
        System.out.println("MJPEG stream    →  http://localhost:" + port + "/stitch");
        System.out.println("Single snapshot →  http://localhost:" + port + "/snapshot");
        System.out.println("Metadata        →  http://localhost:" + port + "/meta");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nShutting down — releasing cameras...");
            for (VideoCapture c : captures) {
                if (c != null) c.release();
            }
            server.stop(1);
        }));
    }

    // -----------------------------------------------------------------
    //  Camera management
    // -----------------------------------------------------------------

    static VideoCapture[] openCameras(int[] indices) {
        VideoCapture[] caps = new VideoCapture[indices.length];
        for (int i = 0; i < indices.length; i++) {
            caps[i] = new VideoCapture(indices[i]);
            if (!caps[i].isOpened()) {
                System.err.println("Cannot open camera at index " + indices[i]);
                for (int j = 0; j <= i; j++) caps[j].release();
                return null;
            }
        }
        return caps;
    }

    static String[] buildCameraNames(int[] indices) {
        String[] defaultLabels = {"front", "rear", "left", "right"};
        String[] names = new String[indices.length];
        for (int i = 0; i < indices.length; i++) {
            names[i] = (i < defaultLabels.length) ? defaultLabels[i] : "cam" + indices[i];
        }
        return names;
    }

    static String formatIndices(int[] indices) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < indices.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(indices[i]);
        }
        return sb.append("]").toString();
    }

    // -----------------------------------------------------------------
    //  Live MJPEG stitch handler
    // -----------------------------------------------------------------

    static class LiveStitchHandler implements HttpHandler {
        private final VideoCapture[] captures;

        LiveStitchHandler(VideoCapture[] captures) {
            this.captures = captures;
        }

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

            StitchingCore.StitchWorkspace ws =
                    new StitchingCore.StitchWorkspace(TARGET_HEIGHT, TARGET_WIDTH, OVERLAP_PX);

            try (OutputStream out = ex.getResponseBody()) {
                Mat[] frames = new Mat[captures.length];
                Mat[] resized = new Mat[captures.length];
                for (int i = 0; i < captures.length; i++) {
                    frames[i] = new Mat();
                    resized[i] = new Mat();
                }

                while (true) {
                    boolean ok = captureAndResize(captures, frames, resized,
                            TARGET_WIDTH, TARGET_HEIGHT);
                    if (!ok) break;

                    Mat panorama = StitchingCore.featherStitchOptimised(
                            resized, TARGET_WIDTH, TARGET_HEIGHT, OVERLAP_PX, ws);
                    StitchingCore.writeMjpegFrame(out,
                            StitchingCore.encodeJpeg(panorama, JPEG_QUALITY));
                    panorama.release();

                    Thread.sleep(FRAME_DELAY_MS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                ws.release();
            }
        }
    }

    // -----------------------------------------------------------------
    //  Single-frame snapshot handler
    // -----------------------------------------------------------------

    static class SnapshotHandler implements HttpHandler {
        private final VideoCapture[] captures;

        SnapshotHandler(VideoCapture[] captures) {
            this.captures = captures;
        }

        @Override
        public void handle(HttpExchange ex) throws IOException {
            if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
                ex.sendResponseHeaders(405, -1);
                return;
            }

            Mat[] frames = new Mat[captures.length];
            Mat[] resized = new Mat[captures.length];
            for (int i = 0; i < captures.length; i++) {
                frames[i] = new Mat();
                resized[i] = new Mat();
            }

            boolean ok = captureAndResize(captures, frames, resized,
                    TARGET_WIDTH, TARGET_HEIGHT);
            if (!ok) {
                ex.sendResponseHeaders(500, -1);
                return;
            }

            Mat panorama = StitchingCore.featherStitch(
                    resized, TARGET_WIDTH, TARGET_HEIGHT, OVERLAP_PX);
            byte[] jpeg = StitchingCore.encodeJpeg(panorama, JPEG_QUALITY);
            panorama.release();

            ex.getResponseHeaders().set("Content-Type", "image/jpeg");
            ex.getResponseHeaders().set("Cache-Control", "no-cache");
            ex.sendResponseHeaders(200, jpeg.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(jpeg);
            }
        }
    }

    // -----------------------------------------------------------------
    //  Shared helpers
    // -----------------------------------------------------------------

    static boolean captureAndResize(VideoCapture[] captures, Mat[] frames,
                                    Mat[] resized, int w, int h) {
        synchronized (captures) {
            for (int i = 0; i < captures.length; i++) {
                if (!captures[i].read(frames[i]) || frames[i].empty()) {
                    return false;
                }
                Imgproc.resize(frames[i], resized[i], new Size(w, h));
            }
        }
        return true;
    }
}
