package com.middleware.panorama;

import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared stitching, blending, and encoding utilities used by both
 * the file-based and camera-based streaming servers.
 *
 * Thread-safety: instances are NOT thread-safe — each request thread
 * should either create its own instance or call the static helpers
 * (which are stateless / allocate locally).
 */
public final class StitchingCore {

    private StitchingCore() {}

    // -----------------------------------------------------------------
    //  Panorama geometry (pure math — no OpenCV dependency)
    // -----------------------------------------------------------------

    public static int clampOverlap(int overlap, int frameWidth) {
        return Math.min(Math.max(overlap, 0), frameWidth / 4);
    }

    public static int panoramaWidth(int n, int frameWidth, int overlap) {
        if (n <= 0) return 0;
        return frameWidth + (n - 1) * (frameWidth - overlap);
    }

    public static int cameraX(int index, int frameWidth, int overlap) {
        return index * (frameWidth - overlap);
    }

    public static int cameraVisibleWidth(int index, int frameWidth, int overlap, int panoWidth) {
        int x = cameraX(index, frameWidth, overlap);
        return (x + frameWidth <= panoWidth) ? frameWidth : (panoWidth - x);
    }

    // -----------------------------------------------------------------
    //  Feather mask — built once per (H, W, overlap) triple
    // -----------------------------------------------------------------

    public static Mat buildFeatherMask(int H, int W, int overlap) {
        Mat mask = new Mat(H, W, CvType.CV_32FC1, new Scalar(1.0));
        for (int x = 0; x < overlap; x++) {
            float alpha = (float) x / overlap;
            for (int y = 0; y < H; y++) {
                mask.put(y, x, new float[]{alpha});
                mask.put(y, W - 1 - x, new float[]{alpha});
            }
        }
        return mask;
    }

    // -----------------------------------------------------------------
    //  Feather-blend stitching
    // -----------------------------------------------------------------

    public static Mat featherStitch(Mat[] frames, int targetWidth, int targetHeight, int overlapPx) {
        int N = frames.length;
        int H = targetHeight;
        int W = targetWidth;
        int overlap = clampOverlap(overlapPx, W);
        int panoW = panoramaWidth(N, W, overlap);

        Mat accumColor = Mat.zeros(H, panoW, CvType.CV_32FC3);
        Mat accumWeight = Mat.zeros(H, panoW, CvType.CV_32FC1);

        for (int i = 0; i < N; i++) {
            int xStart = cameraX(i, W, overlap);

            Mat weight = buildFeatherMask(H, W, overlap);

            Mat frameF = new Mat();
            frames[i].convertTo(frameF, CvType.CV_32FC3);

            Mat weight3 = new Mat();
            List<Mat> ch = new ArrayList<>();
            ch.add(weight);
            ch.add(weight);
            ch.add(weight);
            Core.merge(ch, weight3);

            Mat wFrame = new Mat();
            Core.multiply(frameF, weight3, wFrame);

            int xEnd = Math.min(xStart + W, panoW);
            int wActual = xEnd - xStart;

            Mat colorRoi = accumColor.submat(0, H, xStart, xEnd);
            Mat weightRoi = accumWeight.submat(0, H, xStart, xEnd);

            Mat wFrameCrop = wFrame.colRange(0, wActual);
            Mat weightCrop = weight.colRange(0, wActual);

            Core.add(colorRoi, wFrameCrop, colorRoi);
            Core.add(weightRoi, weightCrop, weightRoi);

            colorRoi.release();
            weightRoi.release();
            frameF.release();
            weight.release();
            weight3.release();
            wFrame.release();
        }

        Mat safeW = new Mat();
        Core.max(accumWeight, new Scalar(1e-6), safeW);

        Mat safeW3 = new Mat();
        List<Mat> wch = new ArrayList<>();
        wch.add(safeW);
        wch.add(safeW);
        wch.add(safeW);
        Core.merge(wch, safeW3);

        Mat blended = new Mat();
        Core.divide(accumColor, safeW3, blended);

        Mat result = new Mat();
        blended.convertTo(result, CvType.CV_8UC3);

        accumColor.release();
        accumWeight.release();
        safeW.release();
        safeW3.release();
        blended.release();

        return result;
    }

    // -----------------------------------------------------------------
    //  Optimised feather-blend stitching — pre-allocated mask reuse
    // -----------------------------------------------------------------

    /**
     * Mutable workspace for {@link #featherStitchOptimised} so per-frame
     * allocations are avoided on the hot path.
     */
    public static final class StitchWorkspace {
        private Mat mask;
        private Mat mask3;
        private Mat accumColor;
        private Mat accumWeight;
        private Mat frameF;
        private Mat wFrame;
        private Mat safeW;
        private Mat safeW3;
        private Mat blended;
        private final int height;
        private final int width;
        private final int overlap;

        public StitchWorkspace(int height, int width, int overlap) {
            this.height = height;
            this.width = width;
            this.overlap = clampOverlap(overlap, width);
            this.mask = buildFeatherMask(height, width, this.overlap);

            List<Mat> ch = new ArrayList<>();
            ch.add(mask);
            ch.add(mask);
            ch.add(mask);
            this.mask3 = new Mat();
            Core.merge(ch, mask3);
        }

        public void release() {
            if (mask != null) mask.release();
            if (mask3 != null) mask3.release();
            if (accumColor != null) accumColor.release();
            if (accumWeight != null) accumWeight.release();
            if (frameF != null) frameF.release();
            if (wFrame != null) wFrame.release();
            if (safeW != null) safeW.release();
            if (safeW3 != null) safeW3.release();
            if (blended != null) blended.release();
        }
    }

    public static Mat featherStitchOptimised(Mat[] frames, int targetWidth, int targetHeight,
                                              int overlapPx, StitchWorkspace ws) {
        int N = frames.length;
        int H = targetHeight;
        int W = targetWidth;
        int overlap = ws.overlap;
        int panoW = panoramaWidth(N, W, overlap);

        if (ws.accumColor == null || ws.accumColor.cols() != panoW) {
            if (ws.accumColor != null) ws.accumColor.release();
            if (ws.accumWeight != null) ws.accumWeight.release();
            ws.accumColor = Mat.zeros(H, panoW, CvType.CV_32FC3);
            ws.accumWeight = Mat.zeros(H, panoW, CvType.CV_32FC1);
        } else {
            ws.accumColor.setTo(Scalar.all(0));
            ws.accumWeight.setTo(Scalar.all(0));
        }

        for (int i = 0; i < N; i++) {
            int xStart = cameraX(i, W, overlap);

            if (ws.frameF == null) ws.frameF = new Mat();
            frames[i].convertTo(ws.frameF, CvType.CV_32FC3);

            if (ws.wFrame == null) ws.wFrame = new Mat();
            Core.multiply(ws.frameF, ws.mask3, ws.wFrame);

            int xEnd = Math.min(xStart + W, panoW);
            int wActual = xEnd - xStart;

            Mat colorRoi = ws.accumColor.submat(0, H, xStart, xEnd);
            Mat weightRoi = ws.accumWeight.submat(0, H, xStart, xEnd);

            Mat wFrameCrop = ws.wFrame.colRange(0, wActual);
            Mat weightCrop = ws.mask.colRange(0, wActual);

            Core.add(colorRoi, wFrameCrop, colorRoi);
            Core.add(weightRoi, weightCrop, weightRoi);

            colorRoi.release();
            weightRoi.release();
        }

        if (ws.safeW == null) ws.safeW = new Mat();
        Core.max(ws.accumWeight, new Scalar(1e-6), ws.safeW);

        if (ws.safeW3 == null) ws.safeW3 = new Mat();
        List<Mat> wch = new ArrayList<>();
        wch.add(ws.safeW);
        wch.add(ws.safeW);
        wch.add(ws.safeW);
        Core.merge(wch, ws.safeW3);

        if (ws.blended == null) ws.blended = new Mat();
        Core.divide(ws.accumColor, ws.safeW3, ws.blended);

        Mat result = new Mat();
        ws.blended.convertTo(result, CvType.CV_8UC3);

        return result;
    }

    // -----------------------------------------------------------------
    //  JPEG encoding
    // -----------------------------------------------------------------

    public static byte[] encodeJpeg(Mat frame, int quality) {
        MatOfByte buf = new MatOfByte();
        MatOfInt params = new MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, quality);
        Imgcodecs.imencode(".jpg", frame, buf, params);
        return buf.toArray();
    }

    public static byte[] encodeJpeg(Mat frame) {
        return encodeJpeg(frame, 88);
    }

    // -----------------------------------------------------------------
    //  MJPEG frame writer
    // -----------------------------------------------------------------

    public static void writeMjpegFrame(java.io.OutputStream out, byte[] jpeg) throws java.io.IOException {
        String header = "--frame\r\nContent-Type: image/jpeg\r\nContent-Length: "
                + jpeg.length + "\r\n\r\n";
        out.write(header.getBytes("UTF-8"));
        out.write(jpeg);
        out.write("\r\n".getBytes("UTF-8"));
        out.flush();
    }
}
