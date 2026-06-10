package com.middleware.panorama;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Executors;

import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;
import org.opencv.videoio.VideoCapture;

/**
 * File-based panoramic stitching server.
 * Reads from four pre-recorded video files and serves a feather-blended
 * MJPEG panorama stream.
 */
public class VideoStreamingServer {

    static final int DEFAULT_PORT  = 9090;
    static final int TARGET_HEIGHT = 360;
    static final int TARGET_WIDTH  = 640;
    static final int OVERLAP_PX    = 80;

    public static void main(String[] args) throws IOException {
        Path frontVideo = Paths.get("right.mov");
        Path rearVideo  = Paths.get("rear.mov");
        Path sideVideo  = Paths.get("left.mov");
        Path backVideo  = Paths.get("Front.mp4");

        Path[] videos = {frontVideo, rearVideo, sideVideo, backVideo};

        for (Path v : videos) {
            if (!Files.exists(v) || Files.isDirectory(v)) {
                System.err.println("Video file not found: " + v);
                return;
            }
        }

        int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;

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

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        String[] cameraNames = {"front", "rear", "left", "right"};
        server.createContext("/stitch", new StitchHandler(videos));
        server.createContext("/play", new CommonHandlers.PlayerPageHandler());
        server.createContext("/meta", new CommonHandlers.MetaHandler(port, cameraNames,
                TARGET_WIDTH, TARGET_HEIGHT, OVERLAP_PX));

        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();

        System.out.println("Streamed data  →  http://localhost:" + port + "/play");
        System.out.println("Metadata       →  http://localhost:" + port + "/meta");
    }

    // -----------------------------------------------------------------

    static class StitchHandler implements HttpHandler {
        private final Path[] videoFiles;

        StitchHandler(Path[] f) {
            this.videoFiles = f;
        }

        @Override
        public void handle(HttpExchange ex) throws IOException {
            if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
                ex.sendResponseHeaders(405, -1);
                return;
            }

            VideoCapture[] caps = new VideoCapture[videoFiles.length];
            for (int i = 0; i < videoFiles.length; i++) {
                caps[i] = new VideoCapture(videoFiles[i].toString());
                if (!caps[i].isOpened()) {
                    releaseCaptures(caps);
                    ex.sendResponseHeaders(500, -1);
                    return;
                }
            }

            ex.getResponseHeaders().set("Content-Type",
                    "multipart/x-mixed-replace; boundary=frame");
            ex.sendResponseHeaders(200, 0);

            StitchingCore.StitchWorkspace ws =
                    new StitchingCore.StitchWorkspace(TARGET_HEIGHT, TARGET_WIDTH, OVERLAP_PX);

            try (OutputStream out = ex.getResponseBody()) {
                Mat[] frames  = new Mat[videoFiles.length];
                Mat[] resized = new Mat[videoFiles.length];
                for (int i = 0; i < videoFiles.length; i++) {
                    frames[i]  = new Mat();
                    resized[i] = new Mat();
                }

                while (true) {
                    boolean done = false;
                    for (int i = 0; i < caps.length; i++) {
                        if (!caps[i].read(frames[i]) || frames[i].empty()) {
                            done = true;
                            break;
                        }
                        Imgproc.resize(frames[i], resized[i],
                                new Size(TARGET_WIDTH, TARGET_HEIGHT));
                    }
                    if (done) break;

                    Mat panorama = StitchingCore.featherStitchOptimised(
                            resized, TARGET_WIDTH, TARGET_HEIGHT, OVERLAP_PX, ws);
                    StitchingCore.writeMjpegFrame(out, StitchingCore.encodeJpeg(panorama));
                    panorama.release();
                }
            } finally {
                ws.release();
                releaseCaptures(caps);
            }
        }
    }

    static void releaseCaptures(VideoCapture[] caps) {
        for (VideoCapture c : caps) {
            if (c != null) c.release();
        }
    }
}
