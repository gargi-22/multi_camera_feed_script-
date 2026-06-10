package com.middleware.panorama;

/**
 * Pure-Java helper that produces the /meta JSON response.
 * Fully testable without OpenCV.
 */
public final class MetaBuilder {

    private MetaBuilder() {}

    /**
     * Build the camera-layout JSON for the given panorama parameters.
     *
     * @param baseUrl     scheme + host (e.g. "http://localhost:9090")
     * @param names       camera names in stitch order
     * @param targetWidth single-camera frame width
     * @param targetHeight single-camera frame height
     * @param overlapPx   overlap between adjacent cameras
     * @return pretty-printed JSON string
     */
    public static String buildJson(String baseUrl, String[] names, int targetWidth,
                                   int targetHeight, int overlapPx) {
        int N = names.length;
        int overlap = StitchingCore.clampOverlap(overlapPx, targetWidth);
        int panoW = StitchingCore.panoramaWidth(N, targetWidth, overlap);
        int panoH = targetHeight;

        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"panorama\": {\n");
        sb.append("    \"width\": ").append(panoW).append(",\n");
        sb.append("    \"height\": ").append(panoH).append(",\n");
        sb.append("    \"player_url\": \"").append(baseUrl).append("/play\"\n");
        sb.append("  },\n");
        sb.append("  \"cameras\": {\n");

        for (int i = 0; i < N; i++) {
            int x = StitchingCore.cameraX(i, targetWidth, overlap);
            int w = StitchingCore.cameraVisibleWidth(i, targetWidth, overlap, panoW);

            sb.append("    \"").append(names[i]).append("\": {\n");
            sb.append("      \"x\": ").append(x).append(",\n");
            sb.append("      \"y\": 0,\n");
            sb.append("      \"w\": ").append(w).append(",\n");
            sb.append("      \"h\": ").append(panoH).append(",\n");
            sb.append("      \"source_w\": ").append(targetWidth).append(",\n");
            sb.append("      \"source_h\": ").append(targetHeight).append(",\n");
            sb.append("      \"overlap_px\": ").append(overlap).append("\n");
            sb.append("    }");
            if (i < N - 1) sb.append(",");
            sb.append("\n");
        }

        sb.append("  }\n}");
        return sb.toString();
    }
}
