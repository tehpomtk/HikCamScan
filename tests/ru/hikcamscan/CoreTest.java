package ru.hikcamscan;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.io.*;
import java.util.*;
import com.sun.net.httpserver.HttpServer;

public final class CoreTest {
    static int checks;
    static void check(boolean value, String label) { if (!value) throw new AssertionError(label); checks++; }
    static void rejects(RunnableThrowing r) throws Exception { try { r.run(); } catch (Exception expected) { checks++; return; } throw new AssertionError("Expected rejection"); }
    interface RunnableThrowing { void run() throws Exception; }
    public static void main(String[] args) throws Exception {
        check(Camera.privateIp("192.168.1.64"), "LAN IPv4");
        check(Camera.privateIp("172.31.0.1"), "Private /12");
        for (String ip : List.of("8.8.8.8", "127.0.0.1", "192.168.1.999", "192.168.01.1", "172.32.0.1", "localhost", "::1")) check(!Camera.privateIp(ip), "Reject " + ip);
        rejects(() -> Camera.sameHost("http://192.168.1.9/image", "192.168.1.8"));
        rejects(() -> Camera.sameHost("file:///etc/passwd", "192.168.1.8"));
        rejects(() -> Camera.sameHost("http://admin:secret@192.168.1.8/image", "192.168.1.8"));
        check(Camera.sameHost("http://192.168.1.8:8000/image?a=1", "192.168.1.8").getPort() == 8000, "Media port");
        String xml = "<d:ProbeMatch xmlns:d='urn:test'><d:XAddrs>http://192.168.1.8/onvif/device_service</d:XAddrs></d:ProbeMatch>";
        check(Xml.text(Xml.parse(xml.getBytes(StandardCharsets.UTF_8)), "XAddrs").contains("192.168.1.8"), "Namespace independent XML");
        rejects(() -> Xml.parse("<!DOCTYPE test [<!ENTITY xxe SYSTEM 'file:///etc/passwd'>]><test>&xxe;</test>".getBytes(StandardCharsets.UTF_8)));
        String auth = CameraHttp.authorization("Digest realm=\"testrealm@host.com\", qop=\"auth,auth-int\", nonce=\"dcd98b7102dd2f0e8b11d0f600bfb0c093\", opaque=\"abc\"", "GET", new URI("http://192.168.1.8/dir/index.html"), new CameraHttp.Credential("Mufasa", "Circle Of Life"));
        String cnonce = auth.split("cnonce=\"")[1].split("\"")[0];
        String expected = CameraHttp.hex("MD5", "939e7578ed9e3c518a452acee763bce9:dcd98b7102dd2f0e8b11d0f600bfb0c093:00000001:" + cnonce + ":auth:39aff3a2bab6126f332b942af96d3366");
        check(auth.contains("response=\"" + expected + "\""), "RFC Digest response");
        rejects(() -> CameraHttp.authorization("Digest realm=\"r\", nonce=\"n\", qop=\"auth-int\"", "GET", new URI("http://192.168.1.8/"), new CameraHttp.Credential("a", "b")));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/ISAPI/System/deviceInfo", exchange -> {
            if (!"Basic YWRtaW46b3duLXBhc3M=".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                exchange.getResponseHeaders().set("WWW-Authenticate", "Basic realm=\"Camera\""); exchange.sendResponseHeaders(401, -1);
                if (exchange.getRequestHeaders().containsKey("Authorization")) attempts.incrementAndGet();
            } else {
                byte[] body = "<DeviceInfo><model>DS-TEST</model><serialNumber>SN123</serialNumber><firmwareVersion>V1</firmwareVersion></DeviceInfo>".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.createContext("/ISAPI/System/Network/interfaces", exchange -> {
            byte[] body = "<NetworkInterfaceList><macAddress>aa:bb:cc:dd:ee:ff</macAddress></NetworkInterfaceList>".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.createContext("/ISAPI/Streaming/channels/101/picture", exchange -> {
            byte[] body = new byte[]{(byte)0xff, (byte)0xd8, (byte)0xff, (byte)0xd9};
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.createContext("/onvif/device_service", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String content;
            if (request.contains("<t:GetDeviceInformation>")) content = "<GetDeviceInformationResponse><Manufacturer>Hikvision</Manufacturer><Model>ONVIF-TEST</Model><FirmwareVersion>V2</FirmwareVersion></GetDeviceInformationResponse>";
            else if (request.contains("<t:GetNetworkInterfaces>")) content = "<GetNetworkInterfacesResponse><Info><HwAddress>11:22:33:44:55:66</HwAddress></Info></GetNetworkInterfacesResponse>";
            else content = "<GetCapabilitiesResponse><Capabilities><Media><XAddr>http://127.0.0.1:" + server.getAddress().getPort() + "/onvif/media</XAddr></Media></Capabilities></GetCapabilitiesResponse>";
            if (!request.contains("#PasswordDigest") || !request.contains("<wsse:Nonce")) throw new AssertionError("Missing WS-Security");
            byte[] body = ("<s:Envelope xmlns:s='http://www.w3.org/2003/05/soap-envelope'><s:Body>" + content + "</s:Body></s:Envelope>").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.createContext("/onvif/media", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String content = request.contains("<t:GetProfiles>") ? "<GetProfilesResponse><Profiles token='main'/></GetProfilesResponse>"
                    : "<GetSnapshotUriResponse><MediaUri><Uri>http://127.0.0.1:" + server.getAddress().getPort() + "/ISAPI/Streaming/channels/101/picture</Uri></MediaUri></GetSnapshotUriResponse>";
            byte[] body = ("<s:Envelope xmlns:s='http://www.w3.org/2003/05/soap-envelope'><s:Body>" + content + "</s:Body></s:Envelope>").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        try {
            Camera c = new Camera("127.0.0.1"); c.port = server.getAddress().getPort(); c.hikvision = true;
            CameraClient.Result result = new CameraClient().connect(c, List.of(new CameraHttp.Credential("admin", "wrong"), new CameraHttp.Credential("admin", "own-pass"), new CameraHttp.Credential("admin", "never")));
            check(result.image().length == 4, "Snapshot bytes");
            check(c.model.equals("DS-TEST") && c.mac.equals("aa:bb:cc:dd:ee:ff"), "Metadata");
            check(attempts.get() == 1, "Stops credentials after success");
            Camera onvif = new Camera("127.0.0.1"); onvif.onvif = true; onvif.port = server.getAddress().getPort();
            CameraClient.Result onvifResult = new CameraClient().connect(onvif, List.of(new CameraHttp.Credential("operator", "own-pass")));
            check(onvifResult.image().length == 4, "ONVIF Media snapshot");
            check(onvif.model.equals("ONVIF-TEST") && onvif.mac.equals("11:22:33:44:55:66"), "ONVIF metadata");
            check(onvif.hikvision, "Manufacturer recognition");
        } finally { server.stop(0); }
        System.out.println("PASS: " + checks + " core checks");
    }
}
