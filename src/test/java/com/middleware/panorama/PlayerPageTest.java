package com.middleware.panorama;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link PlayerPage} — verifies the generated HTML
 * contains essential structural elements.
 */
class PlayerPageTest {

    @Test
    void html_startsWithDoctype() {
        assertTrue(PlayerPage.html().startsWith("<!DOCTYPE html>"));
    }

    @Test
    void html_containsStitchImgSrc() {
        assertTrue(PlayerPage.html().contains("src='/stitch'"));
    }

    @Test
    void html_containsTitle() {
        assertTrue(PlayerPage.html().contains("360° Panoramic"));
    }

    @Test
    void html_isClosedProperly() {
        String h = PlayerPage.html();
        assertTrue(h.contains("</html>"));
        assertTrue(h.contains("</body>"));
    }
}
