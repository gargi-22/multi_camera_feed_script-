# Panorama Stitching Middleware

360° panoramic video stitching server with live-camera and file-based inputs.

## Project Structure

```
src/main/java/com/middleware/panorama/
├── StitchingCore.java          # Shared stitching, blending, encoding utilities
├── MetaBuilder.java            # Pure-Java /meta JSON builder
├── PlayerPage.java             # HTML player page generator
├── CommonHandlers.java         # Shared HTTP handlers (/play, /meta)
├── VideoStreamingServer.java         # File-based panoramic stitching server
├── CameraStreamingServer.java        # Live-camera panoramic stitching server
├── DistributedStitchingServer.java   # Distributed multi-node stitching with MP4 output
└── VideoStreamingClient.java         # Chunked range-request download client
```

## Prerequisites

- **Java 17+**
- **Maven 3.6+**

OpenCV native libraries are bundled via the `org.openpnp:opencv` Maven dependency — no manual OpenCV installation required.

## Build

```bash
mvn clean compile
```

## Run Tests

```bash
mvn test
```

## Usage

### File-based server (reads pre-recorded videos)

```bash
# Place right.mov, rear.mov, left.mov, Front.mp4 in the working directory
mvn exec:java -Dexec.mainClass="com.middleware.panorama.VideoStreamingServer"

# Or with a custom port:
mvn exec:java -Dexec.mainClass="com.middleware.panorama.VideoStreamingServer" -Dexec.args="8080"
```

### Live-camera server (captures from system cameras)

```bash
# Default: port 9091, cameras at indices 0,1,2,3
mvn exec:java -Dexec.mainClass="com.middleware.panorama.CameraStreamingServer"

# Custom port and camera indices:
mvn exec:java -Dexec.mainClass="com.middleware.panorama.CameraStreamingServer" -Dexec.args="8080 0 1"
```

### Endpoints

| Endpoint     | Description                                      |
|-------------|--------------------------------------------------|
| `/play`     | HTML player page (full-viewport panorama viewer) |
| `/stitch`   | MJPEG stream of the stitched panorama            |
| `/meta`     | Camera layout metadata as JSON                   |
| `/snapshot` | Single JPEG frame (camera server only)           |

### Distributed mode (cameras on separate machines, MP4 output)

Run camera nodes on each remote machine, then stitch them on a central server with MP4 recording.

**Step 1 — Start camera node on Machine A** (e.g. IP `192.168.1.10`):

```bash
mvn exec:java -Dexec.mainClass="com.middleware.panorama.CameraStreamingServer" -Dexec.args="9091 0"
```

**Step 2 — Start camera node on Machine B** (e.g. IP `192.168.1.11`):

```bash
mvn exec:java -Dexec.mainClass="com.middleware.panorama.CameraStreamingServer" -Dexec.args="9091 0"
```

**Step 3 — Start the central stitching server** (reads remote streams, records MP4):

```bash
mvn exec:java -Dexec.mainClass="com.middleware.panorama.DistributedStitchingServer" \
  -Dexec.args="panorama.mp4 http://192.168.1.10:9091/stitch http://192.168.1.11:9091/stitch"

# With a custom port:
mvn exec:java -Dexec.mainClass="com.middleware.panorama.DistributedStitchingServer" \
  -Dexec.args="8080 panorama.mp4 http://192.168.1.10:9091/stitch http://192.168.1.11:9091/stitch"
```

The central server:
- Stitches all remote feeds into a live panorama at `/play`
- Simultaneously records the stitched output to an MP4 file
- Press `Ctrl+C` to stop recording and finalize the MP4 file

### Client (download a stream)

```bash
mvn exec:java -Dexec.mainClass="com.middleware.panorama.VideoStreamingClient" \
  -Dexec.args="http://localhost:9091/stitch output.mjpeg"
```

## Optimisations

- **Pre-computed feather mask**: The blending mask is built once per workspace and reused across frames (see `StitchWorkspace`), eliminating per-frame allocation overhead.
- **Buffer reuse**: Accumulation matrices are zeroed-and-reused rather than reallocated each frame.
- **Shared utilities**: Stitching, encoding, and MJPEG writing extracted into `StitchingCore` to avoid code duplication between servers.
- **Client buffer reuse**: The 8KB read buffer is allocated once outside the download loop.
