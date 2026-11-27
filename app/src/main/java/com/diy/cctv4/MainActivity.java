package com.diy.cctv4;

import android.app.Activity;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.PowerManager;
import android.text.format.Formatter;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;

@SuppressWarnings("deprecation")
public class MainActivity extends Activity implements SurfaceHolder.Callback, Camera.PreviewCallback {

    private static final int PORT = 8080;
    private static final String BOUNDARY = "mjpegframe";

    private Camera camera;
    private SurfaceHolder surfaceHolder;
    private TextView tvIpAddress;
    private TextView tvStatus;
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        tvIpAddress = findViewById(R.id.tvIpAddress);
        tvStatus = findViewById(R.id.tvStatus);
        blackOverlay = findViewById(R.id.blackOverlay);
        Button btnSwitchCam = findViewById(R.id.btnSwitchCam);
        Button btnDimScreen = findViewById(R.id.btnDimScreen);

        SurfaceView surfaceView = findViewById(R.id.surfaceView);
        surfaceHolder = surfaceView.getHolder();
        surfaceHolder.addCallback(this);
        surfaceHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DIYCCTV:CpuLock");
        wakeLock.acquire();

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

    private synchronized void initCamera(int cameraId) {
        releaseCamera();
        try {
            int numCameras = Camera.getNumberOfCameras();
            if (numCameras == 0) {
                runOnUiThread(() -> tvStatus.setText("Error: Kamera tidak ditemukan."));
                return;
            }
            if (cameraId >= numCameras) {
                cameraId = 0;
            }
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

            final String camLabel = (currentCameraId == Camera.CameraInfo.CAMERA_FACING_FRONT) ? "Depan" : "Belakang";
            runOnUiThread(() -> tvStatus.setText("Aktif | Kamera: " + camLabel + " (" + previewWidth + "x" + previewHeight + ")"));
        } catch (Exception e) {
            runOnUiThread(() -> tvStatus.setText("Gagal membuka kamera: " + e.getMessage()));
        }
    }

    private void switchCamera() {
        int numCameras = Camera.getNumberOfCameras();
        if (numCameras > 1) {
            isFlashOn = false;
            int nextCam = (currentCameraId == Camera.CameraInfo.CAMERA_FACING_BACK)
                    ? Camera.CameraInfo.CAMERA_FACING_FRONT
                    : Camera.CameraInfo.CAMERA_FACING_BACK;
            initCamera(nextCam);
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

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        initCamera(currentCameraId);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        releaseCamera();
    }

    @Override
    public void onPreviewFrame(byte[] data, Camera cam) {
        if (data == null) return;
        try {
            YuvImage yuv = new YuvImage(data, ImageFormat.NV21, previewWidth, previewHeight, null);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            yuv.compressToJpeg(new Rect(0, 0, previewWidth, previewHeight), jpegQuality, out);
            synchronized (frameLock) {
                latestJpegFrame = out.toByteArray();
            }
        } catch (Exception ignored) {}
    }

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

            // Parsing path URL dari HTTP GET request
            String path = "/";
            String[] parts = requestLine.split(" ");
            if (parts.length >= 2) {
                path = parts[1];
            }

            if (path.startsWith("/stream")) {
                // Endpoint aliran video MJPEG
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
                // Endpoint ambil foto snapshot tunggal
                byte[] frame;
                synchronized (frameLock) {
                    frame = latestJpegFrame;
                }
                if (frame != null) {
                    String header = "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: image/jpeg\r\n" +
                            "Content-Disposition: attachment; filename=\"cctv_snapshot.jpg\"\r\n" +
                            "Content-Length: " + frame.length + "\r\n\r\n";
                    out.write(header.getBytes());
                    out.write(frame);
                    out.flush();
                }
            } else {
                // Eksekusi perintah kontrol dari web
                if (path.contains("cmd=flash")) {
                    runOnUiThread(this::toggleFlash);
                } else if (path.contains("cmd=switch")) {
                    runOnUiThread(this::switchCamera);
                } else if (path.contains("q=low")) {
                    jpegQuality = 35;
                } else if (path.contains("q=med")) {
                    jpegQuality = 60;
                } else if (path.contains("q=high")) {
                    jpegQuality = 85;
                }

                // Kirim halaman Web Dashboard ke browser
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
        return "<!DOCTYPE html><html><head>" +
                "<meta name='viewport' content='width=device-width, initial-scale=1.0'>" +
                "<title>DIY CCTV Monitor</title>" +
                "<style>" +
                "body{background:#121212;color:#fff;font-family:sans-serif;text-align:center;margin:0;padding:12px;}" +
                "img{width:100%;max-width:720px;border:2px solid #333;border-radius:8px;background:#000;}" +
                ".controls{margin-top:12px;display:flex;flex-wrap:wrap;justify-content:center;gap:8px;}" +
                "a.btn{background:#1e88e5;color:#fff;text-decoration:none;padding:10px 14px;border-radius:6px;font-size:14px;font-weight:bold;}" +
                "a.btn-warn{background:#f57c00;}" +
                "a.btn-green{background:#2e7d32;}" +
                "a.btn-gray{background:#424242;}" +
                "</style></head><body>" +
                "<h3>Live CCTV Android 4</h3>" +
                "<img src='/stream' alt='Live Stream'/>" +
                "<div class='controls'>" +
                "<a class='btn btn-warn' href='/?cmd=flash'>Lampu Flash</a>" +
                "<a class='btn' href='/?cmd=switch'>Ganti Kamera</a>" +
                "<a class='btn btn-green' href='/shot.jpg' target='_blank'>Simpan Foto</a>" +
                "</div>" +
                "<div class='controls'>" +
                "<a class='btn btn-gray' href='/?q=low'>Kualitas Rendah (Hemat Kuota)</a>" +
                "<a class='btn btn-gray' href='/?q=med'>Sedang</a>" +
                "<a class='btn btn-gray' href='/?q=high'>Tinggi</a>" +
                "</div>" +
                "</body></html>";
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
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {}
        releaseCamera();
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        super.onDestroy();
    }
}
