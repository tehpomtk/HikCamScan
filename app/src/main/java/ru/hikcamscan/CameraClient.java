package ru.hikcamscan;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;
import org.w3c.dom.*;

final class CameraClient {
    record Result(byte[] image, String note) { }
    private static final String DEVICE = "http://www.onvif.org/ver10/device/wsdl";
    private static final String MEDIA = "http://www.onvif.org/ver10/media/wsdl";
    Result connect(Camera c, List<CameraHttp.Credential> credentials) throws Exception {
        for (int i = 0; i < credentials.size(); i++) {
            try {
                if (c.hikvision || !c.onvif) {
                    CameraHttp.Response info = CameraHttp.request(c.base() + "/ISAPI/System/deviceInfo", "GET", null, null, credentials.get(i), c.ip);
                    if (info.code() == 200) {
                        Document doc = Xml.parse(info.body());
                        if (Xml.text(doc, "model").isEmpty()) throw new IOException("Ответ не содержит данных камеры Hikvision");
                        c.hikvision = true; c.model = Xml.text(doc, "model"); c.serial = Xml.text(doc, "serialNumber");
                        c.firmware = Xml.text(doc, "firmwareVersion"); c.name = Xml.text(doc, "deviceName");
                        try {
                            CameraHttp.Response net = CameraHttp.request(c.base() + "/ISAPI/System/Network/interfaces", "GET", null, null, credentials.get(i), c.ip);
                            if (net.code() == 200) { String mac = Xml.text(Xml.parse(net.body()), "macAddress"); if (!mac.isEmpty()) c.mac = mac; }
                        } catch (Exception ignored) { }
                        try {
                            CameraHttp.Response image = CameraHttp.request(c.base() + "/ISAPI/Streaming/channels/101/picture", "GET", null, null, credentials.get(i), c.ip);
                            if (image.code() == 200) return new Result(image.body(), "Вход выполнен · ISAPI");
                            return new Result(null, "Вход выполнен · снимок HTTP " + image.code());
                        } catch (Exception e) { return new Result(null, "Вход выполнен · снимок недоступен: " + message(e)); }
                    }
                    if (info.code() != 404 && info.code() != 405 && info.code() != 501)
                        throw new IOException("ISAPI HTTP " + info.code());
                }
                return onvif(c, credentials.get(i));
            } catch (CameraHttp.AuthException e) {
                if (i + 1 == credentials.size()) throw new IOException("Учётные записи не подошли (" + credentials.size() + "). Проверка остановлена.");
                Thread.sleep(2000);
            }
        }
        throw new IOException("Добавьте учётную запись");
    }
    private Result onvif(Camera c, CameraHttp.Credential credential) throws Exception {
        String device = c.deviceUrl.isEmpty() ? c.base() + "/onvif/device_service" : c.deviceUrl;
        Document info = soap(c, device, DEVICE, "GetDeviceInformation", "", credential);
        c.onvif = true; c.model = Xml.text(info, "Model"); c.firmware = Xml.text(info, "FirmwareVersion");
        c.serial = Xml.text(info, "SerialNumber");
        if (Xml.text(info, "Manufacturer").toLowerCase(Locale.ROOT).contains("hikvision")) c.hikvision = true;
        try {
            Document net = soap(c, device, DEVICE, "GetNetworkInterfaces", "", credential);
            String mac = Xml.text(net, "HwAddress"); if (!mac.isEmpty()) c.mac = mac;
        } catch (Exception ignored) { }
        try {
            Document capabilities = soap(c, device, DEVICE, "GetCapabilities", "<t:Category>Media</t:Category>", credential);
            NodeList nodes = capabilities.getElementsByTagNameNS("*", "Media");
            if (nodes.getLength() == 0) throw new IOException("Камера не вернула Media service");
            String media = Xml.text(nodes.item(0), "XAddr"); Camera.sameHost(media, c.ip);
            Document profiles = soap(c, media, MEDIA, "GetProfiles", "", credential);
            NodeList p = profiles.getElementsByTagNameNS("*", "Profiles");
            if (p.getLength() == 0) throw new IOException("Нет видеопрофилей ONVIF");
            String token = ((Element)p.item(0)).getAttribute("token");
            Document snapshot = soap(c, media, MEDIA, "GetSnapshotUri", "<t:ProfileToken>" + Xml.escape(token) + "</t:ProfileToken>", credential);
            String url = Xml.text(snapshot, "Uri"); Camera.sameHost(url, c.ip);
            CameraHttp.Response image = CameraHttp.request(url, "GET", null, null, credential, c.ip);
            if (image.code() != 200) throw new IOException("Снимок HTTP " + image.code());
            return new Result(image.body(), "Вход выполнен · ONVIF");
        } catch (Exception e) { return new Result(null, "Вход выполнен · снимок недоступен: " + message(e)); }
    }
    private Document soap(Camera c, String endpoint, String namespace, String operation, String inner, CameraHttp.Credential credential) throws Exception {
        byte[] nonce = new byte[20]; new SecureRandom().nextBytes(nonce);
        String created = Instant.now().toString();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); bytes.write(nonce);
        bytes.write(created.getBytes(StandardCharsets.UTF_8)); bytes.write(credential.password().getBytes(StandardCharsets.UTF_8));
        String digest = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest(bytes.toByteArray()));
        String wsse = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd";
        String profile = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0";
        String envelope = "<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\" xmlns:t=\"" + namespace
                + "\" xmlns:wsse=\"" + wsse + "\" xmlns:wsu=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd\">"
                + "<s:Header><wsse:Security s:mustUnderstand=\"1\"><wsse:UsernameToken><wsse:Username>" + Xml.escape(credential.user())
                + "</wsse:Username><wsse:Password Type=\"" + profile + "#PasswordDigest\">" + digest
                + "</wsse:Password><wsse:Nonce EncodingType=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary\">"
                + Base64.getEncoder().encodeToString(nonce) + "</wsse:Nonce><wsu:Created>" + created
                + "</wsu:Created></wsse:UsernameToken></wsse:Security></s:Header><s:Body><t:" + operation + ">" + inner
                + "</t:" + operation + "></s:Body></s:Envelope>";
        CameraHttp.Response response = CameraHttp.request(endpoint, "POST", envelope, namespace + "/" + operation, credential, c.ip);
        Document doc;
        try { doc = Xml.parse(response.body()); } catch (Exception e) { throw new IOException("ONVIF HTTP " + response.code()); }
        if (doc.getElementsByTagNameNS("*", "Fault").getLength() > 0) {
            String fault = doc.getDocumentElement().getTextContent().toLowerCase(Locale.ROOT);
            if (fault.contains("lock") || fault.contains("toomany")) throw new IOException("Камера ограничила вход. Проверка остановлена.");
            if (fault.contains("notauthorized") || fault.contains("failedauthentication")) throw new CameraHttp.AuthException();
            throw new IOException("ONVIF: " + operation + " не выполнен. Проверьте поддержку операции и время камеры.");
        }
        if (response.code() != 200) throw new IOException("ONVIF HTTP " + response.code());
        return doc;
    }
    static String message(Exception e) {
        if (e instanceof java.net.SocketTimeoutException) return "Камера не ответила вовремя";
        if (e instanceof javax.net.ssl.SSLException) return "Ошибка сертификата HTTPS камеры";
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
