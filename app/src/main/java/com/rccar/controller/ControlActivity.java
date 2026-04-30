package com.rccar.controller;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;

/**
 * Ana kontrol ekranı — WiFi UDP üzerinden ESP8266'ya komut gönderir.
 * Telemetri (seq, hız, yön, voltaj) üst panelde gösterilir.
 * Bağlantı kopunca otomatik olarak Setup ekranına dönülür.
 */
public class ControlActivity extends AppCompatActivity {

    private static final int SEND_MS         = 50;    // 20 Hz gönderim
    private static final int TELEMETRY_TIMEOUT_MS = 3000; // 3s telemetri gelmezse bağlantı yok

    // --- Kontrol arayüzü ---
    private SeekBar  sbThrottle, sbSteer;
    private TextView tvStatus;
    private TextView tvThrottle, tvSteer, tvTrim;
    private Button   btnTrimL, btnTrimR, btnReset, btnSetup;

    // --- Telemetri paneli ---
    private TextView tvTelSeq, tvTelThrottle, tvTelSteer, tvTelVoltage;
    private ProgressBar pbVoltage;

    private ConnectionConfig config;
    private UdpManager       udp;

    private int throttle = 0, steer = 0, trim = 0;
    private long lastTelemetryMs = 0;
    private long startTimeMs = 0;
    private boolean disconnectPending = false;
    private ConnectivityManager.NetworkCallback networkCallback;

    private final Handler handler = new Handler(Looper.getMainLooper());

    // 20Hz komut gönderici
    private final Runnable sender = new Runnable() {
        @Override public void run() {
            if (udp.isReady())
                udp.send(RCProtocol.buildPacket(throttle, (steer + trim)));
            handler.postDelayed(this, SEND_MS);
        }
    };

    // Telemetri zaman aşımı kontrolü (500ms'de bir)
    private final Runnable telemetryWatchdog = new Runnable() {
        @Override public void run() {
            long now = System.currentTimeMillis();
            long last = (lastTelemetryMs > 0) ? lastTelemetryMs : startTimeMs;

            if (now - last > TELEMETRY_TIMEOUT_MS) {
                if (!disconnectPending) {
                    disconnectPending = true;
                    onConnectionLost();
                }
            }

            // SSID kontrolü
            checkSsidChanged();

            if (!disconnectPending) {
                handler.postDelayed(this, 1000);
            }
        }
    };

    private void checkSsidChanged() {
        if (disconnectPending) return;
        String targetSsid = config.getSsid();
        if (targetSsid == null || targetSsid.isEmpty()) return;

        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        WifiInfo info = wm.getConnectionInfo();
        if (info != null) {
            String ssid = info.getSSID();
            if (ssid != null) {
                if (ssid.startsWith("\"") && ssid.endsWith("\"")) {
                    ssid = ssid.substring(1, ssid.length() - 1);
                }
                // Eğer SSID farklıysa (ve bilinmeyen değilse) bağlantı kopmuş demektir
                if (!ssid.equals("<unknown ssid>") && !ssid.equals(targetSsid)) {
                    Log.w("ControlActivity", "SSID değişti: " + ssid + " (Beklenen: " + targetSsid + ")");
                    onConnectionLost();
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_control);

        config = new ConnectionConfig(this);
        trim   = config.getTrim();
        udp    = new UdpManager();

        bindViews();
        setupSeekBars();
        setupButtons();
        setupTelemetryListener();
        connectUdp();
    }

    // -------------------------------------------------------------------------
    private void bindViews() {
        sbThrottle    = findViewById(R.id.sbThrottle);
        sbSteer       = findViewById(R.id.sbSteer);
        tvStatus      = findViewById(R.id.tvStatus);
        tvThrottle    = findViewById(R.id.tvThrottle);
        tvSteer       = findViewById(R.id.tvSteer);
        tvTrim        = findViewById(R.id.tvTrim);
        btnTrimL      = findViewById(R.id.btnTrimL);
        btnTrimR      = findViewById(R.id.btnTrimR);
        btnReset      = findViewById(R.id.btnReset);
        btnSetup      = findViewById(R.id.btnSetup);

        // Telemetri
        tvTelSeq      = findViewById(R.id.tvTelSeq);
        tvTelThrottle = findViewById(R.id.tvTelThrottle);
        tvTelSteer    = findViewById(R.id.tvTelSteer);
        tvTelVoltage  = findViewById(R.id.tvTelVoltage);
        pbVoltage     = findViewById(R.id.pbVoltage);

        refreshTrim();
        resetTelemetryDisplay();
    }

    // -------------------------------------------------------------------------
    private void setupSeekBars() {
        sbThrottle.setMax(200); sbThrottle.setProgress(100);
        sbSteer.setMax(200);    sbSteer.setProgress(100);

        sbThrottle.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int p, boolean u) {
                throttle = p - 100;
                tvThrottle.setText("GAZ: " + throttle);
            }
            public void onStartTrackingTouch(SeekBar sb) {}
            public void onStopTrackingTouch(SeekBar sb) {
                sb.setProgress(100); throttle = 0;
                tvThrottle.setText("GAZ: 0");
            }
        });

        sbSteer.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int p, boolean u) {
                steer = p - 100;
                tvSteer.setText("YÖN: " + steer);
            }
            public void onStartTrackingTouch(SeekBar sb) {}
            public void onStopTrackingTouch(SeekBar sb) {
                sb.setProgress(100); steer = 0;
                tvSteer.setText("YÖN: 0");
            }
        });
    }

    // -------------------------------------------------------------------------
    private void setupButtons() {
        btnTrimL.setOnClickListener(v -> { trim = Math.max(-30, trim - 1); refreshTrim(); });
        btnTrimR.setOnClickListener(v -> { trim = Math.min( 30, trim + 1); refreshTrim(); });
        btnReset.setOnClickListener(v -> { trim = 0; refreshTrim(); });
        btnSetup.setOnClickListener(v -> goToSetup());
    }

    private void refreshTrim() {
        tvTrim.setText("TRIM: " + (trim >= 0 ? "+" : "") + trim + "°");
    }

    // -------------------------------------------------------------------------
    private void setupTelemetryListener() {
        udp.setTelemetryListener(data -> runOnUiThread(() -> updateTelemetryUI(data)));
    }

    private void updateTelemetryUI(TelemetryData data) {
        lastTelemetryMs = System.currentTimeMillis();
        disconnectPending = false;

        tvTelSeq.setText("SEQ: " + data.seq);
        tvTelThrottle.setText("HIZ: " + data.throttle);
        tvTelSteer.setText("YON: " + data.steer);
        tvTelVoltage.setText(String.format("BAT: %.2fV", data.voltage));

        // Voltaj progress bar (0-100)
        int pct = (int)(data.voltagePercent() * 100);
        pbVoltage.setProgress(pct);

        // Voltaj rengi
        try {
            tvTelVoltage.setTextColor(Color.parseColor(data.voltageColor()));
        } catch (Exception ignored) {}
    }

    private void resetTelemetryDisplay() {
        tvTelSeq.setText("SEQ: --");
        tvTelThrottle.setText("HIZ: --");
        tvTelSteer.setText("YON: --");
        tvTelVoltage.setText("BAT: -.-V");
        pbVoltage.setProgress(0);
    }

    // -------------------------------------------------------------------------
    private void connectUdp() {
        tvStatus.setText("Bağlanılıyor...");
        startTimeMs = System.currentTimeMillis();
        udp.connect(config.getIp(), config.getPort(), new UdpManager.Listener() {
            public void onConnected() {
                runOnUiThread(() -> {
                    startTimeMs = System.currentTimeMillis();
                    tvStatus.setText("Bağlı ✓  " + config.getIp());
                    handler.postDelayed(sender, SEND_MS);
                    handler.postDelayed(telemetryWatchdog, 500);
                    monitorNetwork();
                });
            }
            public void onDisconnected() {
                runOnUiThread(() -> {
                    tvStatus.setText("Bağlantı kesildi");
                    if (!disconnectPending) onConnectionLost();
                });
            }
            public void onError(String m) {
                runOnUiThread(() -> {
                    tvStatus.setText("Hata: " + m);
                    handler.postDelayed(this::goToSetupDelayed, 1500);
                });
            }
            private void goToSetupDelayed() { goToSetup(); }
        });
    }

    private void monitorNetwork() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build();

        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onLost(@androidx.annotation.NonNull Network network) {
                Log.w("ControlActivity", "WiFi ağı kaybedildi");
                onConnectionLost();
            }
        };
        try {
            cm.registerNetworkCallback(request, networkCallback);
        } catch (Exception e) {
            Log.e("ControlActivity", "Network callback hatası", e);
        }
    }

    // -------------------------------------------------------------------------
    /** Bağlantı kopunca çağrılır — kullanıcıyı bilgilendirir ve Setup'a yönlendirir */
    private void onConnectionLost() {
        if (disconnectPending) return;
        disconnectPending = true;

        runOnUiThread(() -> {
            tvStatus.setText("⚠ Bağlantı koptu — yeniden bağlanılıyor...");
            tvStatus.setTextColor(Color.RED);
            resetTelemetryDisplay();
            // 2 saniye göster, sonra Setup ekranına git
            handler.postDelayed(this::goToSetup, 2000);
        });
    }

    private void goToSetup() {
        disconnectPending = true;
        if (networkCallback != null) {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            try { cm.unregisterNetworkCallback(networkCallback); } catch (Exception ignored) {}
            networkCallback = null;
        }
        config.setTrim(trim);
        handler.removeCallbacks(sender);
        handler.removeCallbacks(telemetryWatchdog);
        udp.send(RCProtocol.buildPacket(0, trim)); // dur komutu
        udp.disconnect();
        Intent i = new Intent(this, SetupActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
        finish();
    }

    // -------------------------------------------------------------------------
    @Override protected void onPause() {
        super.onPause();
        handler.removeCallbacks(sender);
        handler.removeCallbacks(telemetryWatchdog);
        udp.send(RCProtocol.buildPacket(0, trim));
    }

    @Override protected void onResume() {
        super.onResume();
        if (udp.isReady()) {
            handler.postDelayed(sender, SEND_MS);
            handler.postDelayed(telemetryWatchdog, 500);
        }
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        config.setTrim(trim);
        handler.removeCallbacks(sender);
        handler.removeCallbacks(telemetryWatchdog);
        udp.disconnect();
    }
}
