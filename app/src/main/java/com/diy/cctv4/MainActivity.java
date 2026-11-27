package com.diy.cctv4;

import android.app.Activity;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.StatFs;
import android.text.format.Formatter;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.ServerSocket;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

@SuppressWarnings("deprecation")
public class MainActivity extends Activity implements SurfaceHolder.Callback, Camera.PreviewCallback {

    private static final int PORT = 8080;
    private static final String BOUNDARY = "mjpegframe";
    private static final long SEGMENT_DURATION_MS = 5 * 60 * 1000L; // Pecah file tiap 5 menit
    private static final long MIN_FREE_SPACE_MB = 300L; // Sisakan minimal 300 MB di memori

    private Camera camera;
    private SurfaceHolder surfaceHolder;
    private TextView tvIpAddress;
    private TextView tvStatus;
    private Button btnRecord;
    private View blackOverlay;
    private PowerManager.WakeLock wakeLock;

    private ServerSocket serverSocket;
    private volatile boolean isRunning = false;
    private volatile byte[] latestJpegFrame = null;
    private final Object frameLock = new Object();

    private int currentCameraId = Camera.CameraInfo.CAMERA_FACING_BACK;
    private boolean isFlashOn = false;
    private int jpegQuality = 60;
    private int previewWidth = 640;
    private int previewHeight = 480;

    // Variabel Perekaman & Auto Replace
    private volatile boolean isRecording = false;
    private volatile boolean autoReplaceEnabled = true;
    private SimpleMjpegAviWriter aviWriter = null;
    private long segmentStartTime = 0;
    private long lastRecordFrameTime = 0;
    private File recordDir;
    private final Object recordLock = new Object();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        tvIpAddress = findViewById(R.id.tvIpAddress);
        tvStatus = findViewById(R.id.tvStatus);
        btnRecord = findViewById(R.id.btnRecord);
        blackOverlay = findViewById(R.id.blackOverlay);
        Button btnSwitchCam = findViewById(R.id.btnSwitchCam);
        Button btnDimScreen = findViewById(R.id.btnDimScreen);

        // Folder penyimpanan video di Android/data/com.diy.cctv4/files/CCTV_Records
        File baseDir = getExternalFilesDir(null);
        if (baseDir == null) baseDir = getFilesDir();
        recordDir = new File(baseDir, "CCTV_Records");
        if (!recordDir.exists()) recordDir.mkdirs();

        SurfaceView surfaceView = findViewById(R.id.surfaceView);
        surfaceHolder = surfaceView.getHolder();
        surfaceHolder.addCallback(this);
        surfaceHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DIYCCTV:CpuLock");
        wakeLock.acquire();

        btnRecord.setOnClickListener(v -> toggleRecording());
        btnSwitchCam.setOnClickListener(v -> switchCamera());

        btnDimScreen.setOnClickListener(v -> {
            blackOverlay.setVisibility(View.VISIBLE);
            WindowManager.LayoutParams params = getWindow().getAttributes();
            params.screenBrightness = 0.01f;
            getWindow().setAttributes(params);
        });

        blackOverlay.setOnClickListener(v -> {
            blackOverlay.setVisibility(View.GONE);
            WindowManager.LayoutParams params = getWindow().getAttributes();
            params.screenBrightness = -1.0f;
            getWindow().setAttributes(params);
        });

        updateIpDisplay();
        startHttpServer();
    }

    private void updateIpDisplay() {
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wm != null) {
            int ipInt = wm.getConnectionInfo().getIpAddress();
            String ip = Formatter.formatIpAddress(ipInt);
            tvIpAddress.setText("Buka di Browser: http://" + ip + ":" + PORT);
        }
    }

    private void updateStatusText() {
        runOnUiThread(() -> {
            String camLabel = (currentCameraId == Camera.CameraInfo.CAMERA_FACING_FRONT) ? "Depan" : "Belakang";
            String recLabel = isRecording ? "[REC AKTIF]" : "[Standby]";
            String autoLabel = autoReplaceEnabled ? "ON" : "OFF";
            long freeMb = getFreeSpaceMB();
            tvStatus.setText(recLabel + " | Cam: " + camLabel + " | Auto-Replace: " + autoLabel + " | Sisa Memori: " + freeMb + " MB");
            btnRecord.setText(isRecording ? "Stop Rekam" : "Mulai Rekam");
        });
    }

    private synchronized void initCamera(int cameraId) {
        releaseCamera();
        try {
            int numCameras = Camera.getNumberOfCameras();
            if (numCameras == 0) {
                runOnUiThread(() -> tvStatus.setText("Error: Kamera tidak ditemukan."));
                return;
            }
            if (cameraId >= numCameras) cameraId = 0;
            currentCameraId = cameraId;
            camera = Camera.open(currentCameraId);
            Camera.Parameters params = camera.getParameters();

            List<Camera.Size> sizes = params.getSupportedPreviewSizes();
            for (Camera.Size size : sizes) {
                if (size.width <= 640 && size.height <= 480) {
                    previewWidth = size.width;
                    previewHeight = size.height;
                    break;
                }
            }
            params.setPreviewSize(previewWidth, previewHeight);
            params.setPreviewFormat(ImageFormat.NV21);

            if (isFlashOn && params.getSupportedFlashModes() != null &&
                params.getSupportedFlashModes().contains(Camera.Parameters.FLASH_MODE_TORCH)) {
                params.setFlashMode(Camera.Parameters.FLASH_MODE_TORCH);
            }

            camera.setParameters(params);
            camera.setPreviewDisplay(surfaceHolder);
            camera.setPreviewCallback(this);
            camera.startPreview();
            updateStatusText();
        } catch (Exception e) {
            runOnUiThread(() -> tvStatus.setText("Gagal membuka kamera: " + e.getMessage()));
        }
    }

    private void switchCamera() {
        int numCameras = Camera.getNumberOfCameras();
        if (numCameras > 1) {
            boolean wasRecording = isRecording;
            if (wasRecording) stopRecording();
            isFlashOn = false;
            int nextCam = (currentCameraId == Camera.CameraInfo.CAMERA_FACING_BACK)
                    ? Camera.CameraInfo.CAMERA_FACING_FRONT
                    : Camera.CameraInfo.CAMERA_FACING_BACK;
            initCamera(nextCam);
            if (wasRecording) startRecording();
        }
    }

    private synchronized void toggleFlash() {
        if (camera == null) return;
        try {
            Camera.Parameters params = camera.getParameters();
            List<String> flashModes = params.getSupportedFlashModes();
            if (flashModes != null && flashModes.contains(Camera.Parameters.FLASH_MODE_TORCH)) {
                isFlashOn = !isFlashOn;
                params.setFlashMode(isFlashOn ? Camera.Parameters.FLASH_MODE_TORCH : Camera.Parameters.FLASH_MODE_OFF);
                camera.setParameters(params);
            }
        } catch (Exception ignored) {}
    }

    // --- LOGIKA PEREKAMAN VIDEO & AUTO REPLACE ---

    private void toggleRecording() {
        if (isRecording) {
            stopRecording();
        } else {
            startRecording();
        }
    }

    private void startRecording() {
        synchronized (recordLock) {
            if (isRecording) return;
            if (!ensureStorageSpace()) {
                runOnUiThread(() -> tvStatus.setText("Gagal Rekam: Memori penuh & Auto-Replace mati!"));
                return;
            }
            try {
                String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                File videoFile = new File(recordDir, "REC_" + timeStamp + ".avi");
                aviWriter = new SimpleMjpegAviWriter(videoFile, previewWidth, previewHeight, 10);
                segmentStartTime = System.currentTimeMillis();
                isRecording = true;
                updateStatusText();
            } catch (Exception e) {
                isRecording = false;
                runOnUiThread(() -> tvStatus.setText("Error rekam: " + e.getMessage()));
            }
        }
    }

    private void stopRecording() {
        synchronized (recordLock) {
            isRecording = false;
            if (aviWriter != null) {
                try {
                    aviWriter.close();
                } catch (Exception ignored) {}
                aviWriter = null;
            }
            updateStatusText();
        }
    }

    private long getFreeSpaceMB() {
        try {
            StatFs stat = new StatFs(recordDir.getAbsolutePath());
            long bytesAvailable = (long) stat.getBlockSize() * (long) stat.getAvailableBlocks();
            return bytesAvailable / (1024 * 1024);
        } catch (Exception e) {
            return 999;
        }
    }

    private boolean ensureStorageSpace() {
        if (getFreeSpaceMB() >= MIN_FREE_SPACE_MB) return true;
        if (!autoReplaceEnabled) return false;

        File[] files = recordDir.listFiles();
        if (files == null || files.length == 0) return false;

        // Urutkan file dari yang paling lama (lastModified terkecil)
        Arrays.sort(files, new Comparator<File>() {
            @Override
            public int compare(File f1, File f2) {
                return Long.compare(f1.lastModified(), f2.lastModified());
            }
        });

        for (File f : files) {
            if (f.isFile() && f.getName().endsWith(".avi")) {
                f.delete();
                if (getFreeSpaceMB() >= MIN_FREE_SPACE_MB) {
                    return true;
                }
            }
        }
        return getFreeSpaceMB() >= 50; // Minimal sisa darurat 50MB
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        initCamera(currentCameraId);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        stopRecording();
        releaseCamera();
    }

    @Override
    public void onPreviewFrame(byte[] data, Camera cam) {
        if (data == null) return;
        try {
            YuvImage yuv = new YuvImage(data, ImageFormat.NV21, previewWidth, previewHeight, null);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            yuv.compressToJpeg(new Rect(0, 0, previewWidth, previewHeight), jpegQuality, out);
            byte[] jpegBytes = out.toByteArray();

            synchronized (frameLock) {
                latestJpegFrame = jpegBytes;
            }

            // Simpan frame ke video AVI (~10 FPS) jika mode rekam aktif
            if (isRecording) {
                long now = System.currentTimeMillis();
                if (now - lastRecordFrameTime >= 100) {
                    lastRecordFrameTime = now;
                    synchronized (recordLock) {
                        if (isRecording && aviWriter != null) {
                            aviWriter.addFrame(jpegBytes);
                            // Rotasi file otomatis tiap 5 menit
                            if (now - segmentStartTime >= SEGMENT_DURATION_MS) {
                                stopRecording();
                                startRecording();
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    // --- WEB SERVER & DASHBOARD KONTROL ---

    private void startHttpServer() {
        isRunning = true;
        new Thread(() -> {
            try {
                serverSocket = new ServerSocket(PORT);
                while (isRunning) {
                    Socket client = serverSocket.accept();
                    new Thread(() -> handleClient(client)).start();
                }
            } catch (IOException ignored) {}
        }).start();
    }

    private void handleClient(Socket client) {
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
            OutputStream out = client.getOutputStream();

            String requestLine = in.readLine();
            if (requestLine == null) {
                client.close();
                return;
            }

            String path = "/";
            String[] parts = requestLine.split(" ");
            if (parts.length >= 2) path = parts[1];

            if (path.startsWith("/stream")) {
                String header = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: multipart/x-mixed-replace; boundary=" + BOUNDARY + "\r\n" +
                        "Cache-Control: no-cache\r\n" +
                        "Connection: close\r\n\r\n";
                out.write(header.getBytes());
                out.flush();

                while (isRunning && !client.isClosed()) {
                    byte[] frame;
                    synchronized (frameLock) {
                        frame = latestJpegFrame;
                    }
                    if (frame != null) {
                        String frameHeader = "--" + BOUNDARY + "\r\n" +
                                "Content-Type: image/jpeg\r\n" +
                                "Content-Length: " + frame.length + "\r\n\r\n";
                        out.write(frameHeader.getBytes());
                        out.write(frame);
                        out.write("\r\n".getBytes());
                        out.flush();
                    }
                    Thread.sleep(100);
                }
            } else if (path.startsWith("/shot.jpg")) {
                byte[] frame;
                synchronized (frameLock) {
                    frame = latestJpegFrame;
                }
                if (frame != null) {
                    String header = "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: image/jpeg\r\n" +
                            "Content-Disposition: attachment; filename=\"snapshot.jpg\"\r\n" +
                            "Content-Length: " + frame.length + "\r\n\r\n";
                    out.write(header.getBytes());
                    out.write(frame);
                    out.flush();
                }
            } else if (path.startsWith("/download?file=")) {
                String fileName = path.substring("/download?file=".length()).replaceAll("[^a-zA-Z0-9_.-]", "");
                File target = new File(recordDir, fileName);
                if (target.exists() && target.isFile()) {
                    String header = "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: video/x-msvideo\r\n" +
                            "Content-Disposition: attachment; filename=\"" + target.getName() + "\"\r\n" +
                            "Content-Length: " + target.length() + "\r\n\r\n";
                    out.write(header.getBytes());
                    FileInputStream fis = new FileInputStream(target);
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = fis.read(buf)) != -1) {
                        out.write(buf, 0, len);
                    }
                    fis.close();
                    out.flush();
                }
            } else {
                // Proses Perintah Web
                if (path.contains("cmd=flash")) {
                    runOnUiThread(this::toggleFlash);
                } else if (path.contains("cmd=switch")) {
                    runOnUiThread(this::switchCamera);
                } else if (path.contains("cmd=rec")) {
                    runOnUiThread(this::toggleRecording);
                    Thread.sleep(200);
                } else if (path.contains("cmd=autoreplace")) {
                    autoReplaceEnabled = !autoReplaceEnabled;
                    updateStatusText();
                } else if (path.contains("cmd=clearall")) {
                    boolean wasRec = isRecording;
                    if (wasRec) stopRecording();
                    File[] files = recordDir.listFiles();
                    if (files != null) {
                        for (File f : files) f.delete();
                    }
                    if (wasRec) startRecording();
                    updateStatusText();
                } else if (path.contains("q=low")) {
                    jpegQuality = 35;
                } else if (path.contains("q=med")) {
                    jpegQuality = 60;
                } else if (path.contains("q=high")) {
                    jpegQuality = 85;
                }

                String html = buildDashboardHtml();
                byte[] htmlBytes = html.getBytes("UTF-8");
                String header = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: text/html; charset=UTF-8\r\n" +
                        "Content-Length: " + htmlBytes.length + "\r\n\r\n";
                out.write(header.getBytes());
                out.write(htmlBytes);
                out.flush();
            }
        } catch (Exception ignored) {
        } finally {
            try { client.close(); } catch (IOException ignored) {}
        }
    }

    private String buildDashboardHtml() {
        String recBtnText = isRecording ? "STOP REKAM (Sedang Merekam...)" : "MULAI REKAM VIDEO";
        String recBtnClass = isRecording ? "btn-red" : "btn-green";
        String autoText = autoReplaceEnabled ? "Auto-Replace: ON" : "Auto-Replace: OFF";
        String autoClass = autoReplaceEnabled ? "btn-blue" : "btn-gray";

        StringBuilder fileListHtml = new StringBuilder();
        File[] files = recordDir.listFiles();
        if (files != null && files.length > 0) {
            Arrays.sort(files, (f1, f2) -> Long.compare(f2.lastModified(), f1.lastModified()));
            for (File f : files) {
                if (f.isFile() && f.getName().endsWith(".avi")) {
                    long sizeKb = f.length() / 1024;
                    fileListHtml.append("<div class='file-item'>")
                            .append("<span>").append(f.getName()).append(" (").append(sizeKb).append(" KB)</span>")
                            .append("<a class='dl-btn' href='/download?file=").append(f.getName()).append("'>Unduh</a>")
                            .append("</div>");
                }
            }
        } else {
            fileListHtml.append("<p style='color:#888;font-size:13px;'>Belum ada file rekaman tersimpan.</p>");
        }

        return "<!DOCTYPE html><html><head>" +
                "<meta name='viewport' content='width=device-width, initial-scale=1.0'>" +
                "<title>DIY CCTV Monitor</title>" +
                "<style>" +
                "body{background:#121212;color:#fff;font-family:sans-serif;text-align:center;margin:0;padding:12px;}" +
                "img{width:100%;max-width:720px;border:2px solid #333;border-radius:8px;background:#000;}" +
                ".controls{margin-top:10px;display:flex;flex-wrap:wrap;justify-content:center;gap:8px;}" +
                "a.btn{color:#fff;text-decoration:none;padding:10px 14px;border-radius:6px;font-size:13px;font-weight:bold;}" +
                ".btn-blue{background:#1e88e5;} .btn-warn{background:#f57c00;} .btn-green{background:#2e7d32;} .btn-red{background:#c62828;} .btn-gray{background:#424242;}" +
                ".box{max-width:720px;margin:16px auto;background:#1e1e1e;padding:12px;border-radius:8px;text-align:left;}" +
                ".file-item{display:flex;justify-content:space-between;align-items:center;padding:8px 0;border-bottom:1px solid #333;font-size:13px;}" +
                ".dl-btn{background:#2e7d32;color:#fff;padding:5px 10px;border-radius:4px;text-decoration:none;font-weight:bold;}" +
                "</style></head><body>" +
                "<h3>Live CCTV Android 4</h3>" +
                "<img src='/stream' alt='Live Stream'/>" +
                "<div class='controls'>" +
                "<a class='btn " + recBtnClass + "' href='/?cmd=rec'>" + recBtnText + "</a>" +
                "<a class='btn " + autoClass + "' href='/?cmd=autoreplace'>" + autoText + "</a>" +
                "</div>" +
                "<div class='controls'>" +
                "<a class='btn btn-warn' href='/?cmd=flash'>Lampu Flash</a>" +
                "<a class='btn btn-blue' href='/?cmd=switch'>Ganti Kamera</a>" +
                "<a class='btn btn-gray' href='/shot.jpg' target='_blank'>Snapshot Foto</a>" +
                "</div>" +
                "<div class='controls'>" +
                "<a class='btn btn-gray' href='/?q=low'>Kualitas Rendah</a>" +
                "<a class='btn btn-gray' href='/?q=med'>Sedang</a>" +
                "<a class='btn btn-gray' href='/?q=high'>Tinggi</a>" +
                "</div>" +
                "<div class='box'>" +
                "<div style='display:flex;justify-content:space-between;align-items:center;margin-bottom:8px;'>" +
                "<strong>Rekaman Tersimpan (Sisa Memori: " + getFreeSpaceMB() + " MB)</strong>" +
                "<a class='btn btn-red' style='padding:5px 10px;font-size:12px;' href='/?cmd=clearall'>Hapus Semua</a>" +
                "</div>" +
                fileListHtml.toString() +
                "</div></body></html>";
    }

    private synchronized void releaseCamera() {
        if (camera != null) {
            try {
                camera.setPreviewCallback(null);
                camera.stopPreview();
                camera.release();
            } catch (Exception ignored) {}
            camera = null;
        }
    }

    @Override
    protected void onDestroy() {
        isRunning = false;
        stopRecording();
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {}
        releaseCamera();
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        super.onDestroy();
    }

    // --- CLASS INTERNAL: PENULIS VIDEO MJPEG-AVI RINGAN UNTUK ANDROID 4 ---
    private static class SimpleMjpegAviWriter {
        private final File file;
        private final BufferedOutputStream bos;
        private final int width;
        private final int height;
        private final int fps;
        private int frameCount = 0;
        private long moviDataSize = 0;
        private final List<Integer> frameSizes = new ArrayList<>();

        public SimpleMjpegAviWriter(File file, int width, int height, int fps) throws IOException {
            this.file = file;
            this.width = width;
            this.height = height;
            this.fps = fps;
            this.bos = new BufferedOutputStream(new FileOutputStream(file), 32768);
            writeHeaderPlaceholder();
        }

        private void writeHeaderPlaceholder() throws IOException {
            byte[] header = new byte[224];
            bos.write(header);
        }

        public void addFrame(byte[] jpegData) throws IOException {
            int len = jpegData.length;
            int pad = (len % 2 != 0) ? 1 : 0;
            writeFourCC("00dc");
            writeIntLE(len);
            bos.write(jpegData);
            if (pad > 0) bos.write(0);

            frameSizes.add(len);
            frameCount++;
            moviDataSize += (8 + len + pad);
        }

        public void close() throws IOException {
            // Tulis Index (idx1) di akhir file agar durasi & seek video terbaca normal di player
            long idxSize = frameCount * 16L;
            writeFourCC("idx1");
            writeIntLE((int) idxSize);
            int offset = 4;
            for (int size : frameSizes) {
                writeFourCC("00dc");
                writeIntLE(0x10); // AVIIF_KEYFRAME
                writeIntLE(offset);
                writeIntLE(size);
                int pad = (size % 2 != 0) ? 1 : 0;
                offset += (8 + size + pad);
            }
            bos.flush();
            bos.close();

            // Isi ulang Header RIFF AVI menggunakan RandomAccessFile
            RandomAccessFile raf = new RandomAccessFile(file, "rw");
            raf.seek(0);
            long totalRiffSize = 216 + moviDataSize + 8 + idxSize;

            writeRafFourCC(raf, "RIFF");
            writeRafIntLE(raf, (int) totalRiffSize);
            writeRafFourCC(raf, "AVI ");

            writeRafFourCC(raf, "LIST");
            writeRafIntLE(raf, 192);
            writeRafFourCC(raf, "hdrl");

            writeRafFourCC(raf, "avih");
            writeRafIntLE(raf, 56);
            writeRafIntLE(raf, 1000000 / fps); // MicroSecPerFrame
            writeRafIntLE(raf, 1000000);       // MaxBytesPerSec
            writeRafIntLE(raf, 0);
            writeRafIntLE(raf, 0x10);          // AVIF_HASINDEX
            writeRafIntLE(raf, frameCount);    // TotalFrames
            writeRafIntLE(raf, 0);
            writeRafIntLE(raf, 1);             // Streams
            writeRafIntLE(raf, 0);
            writeRafIntLE(raf, width);
            writeRafIntLE(raf, height);
            for (int i = 0; i < 4; i++) writeRafIntLE(raf, 0);

            writeRafFourCC(raf, "LIST");
            writeRafIntLE(raf, 116);
            writeRafFourCC(raf, "strl");

            writeRafFourCC(raf, "strh");
            writeRafIntLE(raf, 56);
            writeRafFourCC(raf, "vids");
            writeRafFourCC(raf, "MJPG");
            writeRafIntLE(raf, 0);
            writeRafIntLE(raf, 0);
            writeRafIntLE(raf, 0);
            writeRafIntLE(raf, 1);             // Scale
            writeRafIntLE(raf, fps);           // Rate
            writeRafIntLE(raf, 0);
            writeRafIntLE(raf, frameCount);    // Length
            writeRafIntLE(raf, 0);
            writeRafIntLE(raf, -1);
            writeRafIntLE(raf, 0);
            writeRafShortLE(raf, (short) 0);
            writeRafShortLE(raf, (short) 0);
            writeRafShortLE(raf, (short) width);
            writeRafShortLE(raf, (short) height);

            writeRafFourCC(raf, "strf");
            writeRafIntLE(raf, 40);
            writeRafIntLE(raf, 40);
            writeRafIntLE(raf, width);
            writeRafIntLE(raf, height);
            writeRafShortLE(raf, (short) 1);
            writeRafShortLE(raf, (short) 24);
            writeRafFourCC(raf, "MJPG");
            writeRafIntLE(raf, width * height * 3);
            for (int i = 0; i < 4; i++) writeRafIntLE(raf, 0);

            writeRafFourCC(raf, "LIST");
            writeRafIntLE(raf, (int) (moviDataSize + 4));
            writeRafFourCC(raf, "movi");
            raf.close();
        }

        private void writeFourCC(String s) throws IOException {
            bos.write(s.getBytes("US-ASCII"));
        }

        private void writeIntLE(int v) throws IOException {
            bos.write(v & 0xFF);
            bos.write((v >> 8) & 0xFF);
            bos.write((v >> 16) & 0xFF);
            bos.write((v >> 24) & 0xFF);
        }

        private void writeRafFourCC(RandomAccessFile raf, String s) throws IOException {
            raf.write(s.getBytes("US-ASCII"));
        }

        private void writeRafIntLE(RandomAccessFile raf, int v) throws IOException {
            raf.write(v & 0xFF);
            raf.write((v >> 8) & 0xFF);
            raf.write((v >> 16) & 0xFF);
            raf.write((v >> 24) & 0xFF);
        }

        private void writeRafShortLE(RandomAccessFile raf, short v) throws IOException {
            raf.write(v & 0xFF);
            raf.write((v >> 8) & 0xFF);
        }
    }
}
