package com.middleware.panorama;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;

/**
 * HTTP handlers shared between the file-based and camera-based servers.
 */
public final class CommonHandlers {

    private CommonHandlers() {}

    // -----------------------------------------------------------------
    //  /play — serves the HTML player page
    // -----------------------------------------------------------------

    static class PlayerPageHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            byte[] bytes = PlayerPage.html().getBytes("UTF-8");
            ex.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    // -----------------------------------------------------------------
    //  /meta — serves camera-layout JSON
    // -----------------------------------------------------------------

    static class MetaHandler implements HttpHandler {
        private final int port;
        private final String[] cameraNames;
        private final int targetWidth;
        private final int targetHeight;
        private final int overlapPx;

        MetaHandler(int port, String[] cameraNames, int targetWidth,
                    int targetHeight, int overlapPx) {
            this.port = port;
            this.cameraNames = cameraNames;
            this.targetWidth = targetWidth;
            this.targetHeight = targetHeight;
            this.overlapPx = overlapPx;
        }

        @Override
        public void handle(HttpExchange ex) throws IOException {
            if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
                ex.sendResponseHeaders(405, -1);
                return;
            }

            String host = ex.getRequestHeaders().getFirst("Host");
            if (host == null || host.isEmpty()) host = "localhost:" + port;
            String baseUrl = "http://" + host;

            String json = MetaBuilder.buildJson(baseUrl, cameraNames,
                    targetWidth, targetHeight, overlapPx);

            byte[] body = json.getBytes("UTF-8");
            ex.getResponseHeaders().set("Content-Type",
                    "application/json; charset=UTF-8");
            ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        }
    }
}
