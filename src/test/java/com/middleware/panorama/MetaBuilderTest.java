package com.middleware.panorama;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link MetaBuilder} — verifies the JSON structure
 * produced for the /meta endpoint. Pure Java, no OpenCV needed.
 */
class MetaBuilderTest {

    private static final String[] NAMES = {"front", "rear", "left", "right"};

    @Test
    void buildJson_containsPanoramaDimensions() {
        String json = MetaBuilder.buildJson("http://localhost:9090", NAMES, 640, 360, 80);
        assertTrue(json.contains("\"width\": 2320"), "panorama width");
        assertTrue(json.contains("\"height\": 360"), "panorama height");
    }

    @Test
    void buildJson_containsPlayerUrl() {
        String json = MetaBuilder.buildJson("http://example.com", NAMES, 640, 360, 80);
        assertTrue(json.contains("\"player_url\": \"http://example.com/play\""));
    }

    @Test
    void buildJson_containsAllCameras() {
        String json = MetaBuilder.buildJson("http://localhost:9090", NAMES, 640, 360, 80);
        for (String name : NAMES) {
            assertTrue(json.contains("\"" + name + "\""), "camera " + name);
        }
    }

    @Test
    void buildJson_firstCameraAtOrigin() {
        String json = MetaBuilder.buildJson("http://localhost:9090", NAMES, 640, 360, 80);
        // front camera: x=0, w=640
        assertTrue(json.contains("\"front\": {\n      \"x\": 0"));
    }

    @Test
    void buildJson_overlapClamped() {
        // overlap 9999 → clamped to 640/4 = 160
        String json = MetaBuilder.buildJson("http://localhost:9090", NAMES, 640, 360, 9999);
        // panoW = 640 + 3*(640-160) = 640 + 1440 = 2080
        assertTrue(json.contains("\"width\": 2080"));
    }

    @Test
    void buildJson_singleCamera() {
        String json = MetaBuilder.buildJson("http://localhost:9090",
                new String[]{"cam0"}, 640, 360, 80);
        assertTrue(json.contains("\"width\": 640"), "single-cam pano = frame width");
        assertTrue(json.contains("\"cam0\""));
        assertFalse(json.contains("\"front\""));
    }

    @Test
    void buildJson_zeroOverlap() {
        String json = MetaBuilder.buildJson("http://localhost:9090", NAMES, 640, 360, 0);
        // panoW = 4 * 640 = 2560
        assertTrue(json.contains("\"width\": 2560"));
        assertTrue(json.contains("\"overlap_px\": 0"));
    }

    @Test
    void buildJson_isValidJson() {
        String json = MetaBuilder.buildJson("http://localhost:9090", NAMES, 640, 360, 80);
        // Basic structural validation
        assertTrue(json.startsWith("{"));
        assertTrue(json.endsWith("}"));
        assertEquals(countOccurrences(json, '{'), countOccurrences(json, '}'));
    }

    private int countOccurrences(String s, char c) {
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) count++;
        }
        return count;
    }
}
