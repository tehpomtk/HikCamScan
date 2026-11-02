package ru.hikcamscan;

import java.net.URI;

final class Camera {
    final String ip;
    int port = 80;
    String model = "", mac = "", serial = "", firmware = "", name = "";
    String deviceUrl = "", status = "Обнаружена", snapshotPath = "", snapshotTime = "";
    boolean hikvision, onvif;
    long lastAttempt;
    Camera(String ip) { this.ip = ip; }
    String base() { return "http://" + ip + ":" + port; }
    void merge(Camera other) {
        hikvision |= other.hikvision; onvif |= other.onvif;
        if (!other.model.isEmpty()) model = other.model;
        if (!other.mac.isEmpty()) mac = other.mac;
        if (!other.serial.isEmpty()) serial = other.serial;
        if (!other.firmware.isEmpty()) firmware = other.firmware;
        if (!other.name.isEmpty()) name = other.name;
        if (!other.deviceUrl.isEmpty()) deviceUrl = other.deviceUrl;
        if (other.hikvision && !other.onvif) port = other.port;
    }
    static URI sameHost(String value, String ip) throws Exception {
        URI uri = new URI(value);
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || !ip.equals(uri.getHost()) || uri.getUserInfo() != null || uri.getFragment() != null)
            throw new java.io.IOException("Камера вернула адрес другого устройства");
        return uri;
    }
    static boolean privateIp(String value) {
        String[] p = value.split("\\.");
        if (p.length != 4) return false;
        int[] n = new int[4];
        try { for (int i = 0; i < 4; i++) {
            if (!p[i].matches("0|[1-9][0-9]{0,2}")) return false;
            n[i] = Integer.parseInt(p[i]); if (n[i] > 255) return false;
        }} catch (NumberFormatException e) { return false; }
        return n[0] == 10 || (n[0] == 172 && n[1] >= 16 && n[1] <= 31)
                || (n[0] == 192 && n[1] == 168) || (n[0] == 169 && n[1] == 254);
    }
}
