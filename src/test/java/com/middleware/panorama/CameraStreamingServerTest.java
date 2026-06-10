package com.middleware.panorama;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for pure-Java helpers in {@link CameraStreamingServer}.
 */
class CameraStreamingServerTest {

    @Test
    void buildCameraNames_defaultLabels() {
        String[] names = CameraStreamingServer.buildCameraNames(new int[]{0, 1, 2, 3});
        assertArrayEquals(new String[]{"front", "rear", "left", "right"}, names);
    }

    @Test
    void buildCameraNames_moreThanFour() {
        String[] names = CameraStreamingServer.buildCameraNames(new int[]{0, 1, 2, 3, 5});
        assertEquals("front", names[0]);
        assertEquals("rear", names[1]);
        assertEquals("left", names[2]);
        assertEquals("right", names[3]);
        assertEquals("cam5", names[4]);
    }

    @Test
    void buildCameraNames_singleCamera() {
        String[] names = CameraStreamingServer.buildCameraNames(new int[]{2});
        assertArrayEquals(new String[]{"front"}, names);
    }

    @Test
    void formatIndices_typical() {
        assertEquals("[0, 1, 2, 3]", CameraStreamingServer.formatIndices(new int[]{0, 1, 2, 3}));
    }

    @Test
    void formatIndices_single() {
        assertEquals("[0]", CameraStreamingServer.formatIndices(new int[]{0}));
    }

    @Test
    void formatIndices_nonContiguous() {
        assertEquals("[0, 2, 4]", CameraStreamingServer.formatIndices(new int[]{0, 2, 4}));
    }
}
