package ru.hikcamscan;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.w3c.dom.*;

final class Discovery {
    private volatile boolean cancelled;
    private final List<DatagramSocket> sockets = new CopyOnWriteArrayList<>();
    void cancel() { cancelled = true; for (DatagramSocket s : sockets) s.close(); }
    void scan(Consumer<Camera> found) throws Exception {
        List<NetworkInterface> interfaces = new ArrayList<>();
        for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!ni.isUp() || ni.isLoopback() || !ni.supportsMulticast()) continue;
            for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                if (a instanceof Inet4Address && Camera.privateIp(a.getHostAddress())) { interfaces.add(ni); break; }
            }
        }
        if (interfaces.isEmpty()) throw new java.io.IOException("Нет локального сетевого интерфейса. Подключитесь к Wi-Fi камер.");
        ExecutorService pool = Executors.newFixedThreadPool(interfaces.size() * 2);
        List<Future<?>> tasks = new ArrayList<>();
        java.util.concurrent.atomic.AtomicInteger opened = new java.util.concurrent.atomic.AtomicInteger();
        try {
            for (NetworkInterface ni : interfaces) for (boolean sadp : new boolean[]{false, true})
                tasks.add(pool.submit(() -> probe(ni, sadp, found, opened)));
            for (Future<?> f : tasks) f.get();
            if (!cancelled && opened.get() == 0) throw new java.io.IOException("Не удалось открыть multicast-сокеты");
        } finally { pool.shutdownNow(); cancel(); }
    }
    private void probe(NetworkInterface ni, boolean sadp, Consumer<Camera> found,
                       java.util.concurrent.atomic.AtomicInteger opened) {
        int port = sadp ? 37020 : 3702;
        try (MulticastSocket socket = new MulticastSocket(null)) {
            sockets.add(socket);
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(sadp ? port : 0));
            socket.setNetworkInterface(ni); socket.setTimeToLive(1); socket.setSoTimeout(500);
            InetAddress group = InetAddress.getByName("239.255.255.250");
            socket.joinGroup(new InetSocketAddress(group, port), ni);
            opened.incrementAndGet();
            String uuid = UUID.randomUUID().toString();
            String payload = sadp ? "<?xml version=\"1.0\" encoding=\"utf-8\"?><Probe><Uuid>" + uuid
                    + "</Uuid><Types>inquiry</Types></Probe>"
                    : "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\" "
                    + "xmlns:a=\"http://schemas.xmlsoap.org/ws/2004/08/addressing\" xmlns:d=\"http://schemas.xmlsoap.org/ws/2005/04/discovery\" "
                    + "xmlns:dn=\"http://www.onvif.org/ver10/network/wsdl\"><s:Header><a:MessageID>uuid:" + uuid
                    + "</a:MessageID><a:To>urn:schemas-xmlsoap-org:ws:2005:04:discovery</a:To>"
                    + "<a:Action>http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</a:Action></s:Header>"
                    + "<s:Body><d:Probe><d:Types>dn:NetworkVideoTransmitter</d:Types></d:Probe></s:Body></s:Envelope>";
            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            long start = System.currentTimeMillis(), sent = 0;
            while (!cancelled && System.currentTimeMillis() - start < 6500) {
                if (System.currentTimeMillis() - sent > 2200) {
                    socket.send(new DatagramPacket(bytes, bytes.length, group, port)); sent = System.currentTimeMillis();
                }
                DatagramPacket packet = new DatagramPacket(new byte[65507], 65507);
                try { socket.receive(packet); } catch (SocketTimeoutException e) { continue; }
                String ip = packet.getAddress().getHostAddress();
                if (!Camera.privateIp(ip)) continue;
                try {
                    Document doc = Xml.parse(Arrays.copyOf(packet.getData(), packet.getLength()));
                    if (sadp) {
                        if (Xml.text(doc, "DeviceDescription").isEmpty() && Xml.text(doc, "DeviceType").isEmpty()) continue;
                        Camera c = new Camera(ip); c.hikvision = true;
                        c.model = Xml.text(doc, "DeviceDescription"); c.mac = Xml.text(doc, "MAC");
                        c.serial = Xml.text(doc, "DeviceSN"); c.firmware = Xml.text(doc, "SoftwareVersion");
                        String p = Xml.text(doc, "HttpPort");
                        if (!p.isEmpty()) { int n = Integer.parseInt(p); if (n > 0 && n <= 65535) c.port = n; }
                        found.accept(c);
                    } else {
                        NodeList matches = doc.getElementsByTagNameNS("*", "ProbeMatch");
                        for (int i = 0; i < matches.getLength(); i++) {
                            Camera c = new Camera(ip); c.onvif = true;
                            for (String x : Xml.text(matches.item(i), "XAddrs").split("\\s+")) {
                                try { Camera.sameHost(x, ip); c.deviceUrl = x; break; } catch (Exception ignored) { }
                            }
                            for (String scope : Xml.text(matches.item(i), "Scopes").split("\\s+")) {
                                if (scope.contains("/hardware/")) c.model = URLDecoder.decode(scope.substring(scope.indexOf("/hardware/") + 10), "UTF-8");
                                if (scope.contains("/name/")) c.name = URLDecoder.decode(scope.substring(scope.indexOf("/name/") + 6), "UTF-8");
                                if (scope.toLowerCase(Locale.ROOT).contains("hikvision")) c.hikvision = true;
                            }
                            found.accept(c);
                        }
                    }
                } catch (Exception ignored) { /* Ignore unrelated/malformed UDP packets. */ }
            }
        } catch (Exception ignored) { /* Other interfaces/protocols may still work. */ }
    }
}
