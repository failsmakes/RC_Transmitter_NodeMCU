package com.rccar.controller;

import java.util.Locale;

public class RCProtocol {
    /**
     * ESP8266'nın beklediği formatta paket hazırlar.
     * Örn: "G:50,Y:-10,T:5"
     */
    public static byte[] buildPacket(int throttle, int steer) {
        String msg = String.format(Locale.US, "{\"G\":%d,\"Y\":%d}", throttle, steer);
        return msg.getBytes();
    }
}
