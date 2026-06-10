package com.middleware.panorama;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure-Java unit tests for the geometry helpers in {@link StitchingCore}.
 * No OpenCV native library required.
 */
class StitchingCoreGeometryTest {

    // -- clampOverlap ---------------------------------------------------------

    @Test
    void clampOverlap_withinBounds() {
        assertEquals(80, StitchingCore.clampOverlap(80, 640));
    }

    @Test
    void clampOverlap_exceedsMax() {
        // max = 640/4 = 160
        assertEquals(160, StitchingCore.clampOverlap(200, 640));
    }

    @Test
    void clampOverlap_negativeClampedToZero() {
        assertEquals(0, StitchingCore.clampOverlap(-5, 640));
    }

    @Test
    void clampOverlap_zero() {
        assertEquals(0, StitchingCore.clampOverlap(0, 640));
    }

    // -- panoramaWidth --------------------------------------------------------

    @ParameterizedTest(name = "n={0}, w={1}, overlap={2} → {3}")
    @CsvSource({
            "4, 640, 80, 2320",      // 640 + 3*(640-80) = 640+1680
            "1, 640, 80, 640",        // single camera = frame width
            "2, 640, 80, 1200",       // 640 + 1*(640-80)
            "0, 640, 80, 0",          // no cameras
            "4, 640, 0,  2560",       // no overlap: 4*640
    })
    void panoramaWidth_parametric(int n, int w, int overlap, int expected) {
        assertEquals(expected, StitchingCore.panoramaWidth(n, w, overlap));
    }

    // -- cameraX --------------------------------------------------------------

    @Test
    void cameraX_firstIsZero() {
        assertEquals(0, StitchingCore.cameraX(0, 640, 80));
    }

    @Test
    void cameraX_secondCamera() {
        assertEquals(560, StitchingCore.cameraX(1, 640, 80));
    }

    @Test
    void cameraX_thirdCamera() {
        assertEquals(1120, StitchingCore.cameraX(2, 640, 80));
    }

    @Test
    void cameraX_fourthCamera() {
        assertEquals(1680, StitchingCore.cameraX(3, 640, 80));
    }

    // -- cameraVisibleWidth ---------------------------------------------------

    @Test
    void cameraVisibleWidth_firstCamera_fullWidth() {
        int panoW = StitchingCore.panoramaWidth(4, 640, 80); // 2320
        assertEquals(640, StitchingCore.cameraVisibleWidth(0, 640, 80, panoW));
    }

    @Test
    void cameraVisibleWidth_lastCamera_fullWidth() {
        int panoW = StitchingCore.panoramaWidth(4, 640, 80); // 2320
        // last camera x=1680, 1680+640=2320 == panoW → full width
        assertEquals(640, StitchingCore.cameraVisibleWidth(3, 640, 80, panoW));
    }

    @Test
    void cameraVisibleWidth_clippedByPanoWidth() {
        // Artificial: panoW smaller than x + frameWidth
        int panoW = 1000;
        // camera 1 at x=560, 560+640=1200 > 1000 → visible = 1000-560 = 440
        assertEquals(440, StitchingCore.cameraVisibleWidth(1, 640, 80, panoW));
    }
}
