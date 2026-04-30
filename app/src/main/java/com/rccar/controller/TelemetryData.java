package com.rccar.controller;

import org.json.JSONObject;

/**
 * ESP8266 Receiver'dan gelen telemetri paketi.
 * JSON format: {"seq":N,"t":T,"s":S,"v":V}
 *   seq : paket sayacı (0-255)
 *   t   : throttle değeri (-100..+100)
 *   s   : steer değeri (-100..+100)
 *   v   : pil voltajı (float, V)
 */
public class TelemetryData {
    public final int   seq;
    public final int   throttle;
    public final int   steer;
    public final float voltage;
    public final long  receivedMs;

    public TelemetryData(int seq, int throttle, int steer, float voltage) {
        this.seq        = seq;
        this.throttle   = throttle;
        this.steer      = steer;
        this.voltage    = voltage;
        this.receivedMs = System.currentTimeMillis();
    }

    /** JSON string'ini parse eder. Hata durumunda null döner. */
    public static TelemetryData parse(String json) {
        try {
            JSONObject o = new JSONObject(json);
            int   seq      = o.optInt("seq",  0);
            int   throttle = o.optInt("t",    0);
            int   steer    = o.optInt("s",    0);
            float voltage  = (float) o.optDouble("v", 0.0);
            return new TelemetryData(seq, throttle, steer, voltage);
        } catch (Exception e) {
            return null;
        }
    }

    /** Voltaj seviyesini 0.0-1.0 arasında döner (2S LiPo varsayımı: 6.0V-8.4V) */
    public float voltagePercent() {
        final float MIN_V = 6.0f;
        final float MAX_V = 8.4f;
        return Math.max(0f, Math.min(1f, (voltage - MIN_V) / (MAX_V - MIN_V)));
    }

    /** Voltaja göre renk kodu (hex string) */
    public String voltageColor() {
        float pct = voltagePercent();
        if (pct > 0.5f) return "#4CAF50";   // yeşil
        if (pct > 0.2f) return "#FF9800";   // turuncu
        return "#F44336";                    // kırmızı
    }
}
