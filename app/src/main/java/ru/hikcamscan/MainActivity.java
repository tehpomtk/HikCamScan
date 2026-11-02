package ru.hikcamscan;

import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
    private final int bg = Color.rgb(12, 20, 25), panel = Color.rgb(22, 34, 41), muted = Color.rgb(149, 168, 177), green = Color.rgb(81, 224, 173);
    private final Map<String, Camera> cameras = new LinkedHashMap<>();
    private final List<CameraHttp.Credential> credentials = new ArrayList<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private LinearLayout list;
    private TextView status, count;
    private Button scan, connectAll;
    private volatile Discovery discovery;
    private boolean busy, destroyed;
    private WifiManager.MulticastLock multicastLock;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setStatusBarColor(bg); getWindow().setNavigationBarColor(bg);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        LinearLayout root = column(); root.setBackgroundColor(bg); root.setPadding(dp(20), dp(14), dp(20), 0);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(dp(20) + insets.getSystemWindowInsetLeft(), dp(14) + insets.getSystemWindowInsetTop(),
                    dp(20) + insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(root);
        TextView brand = label("◉  HIKCAMSCAN", 26, Color.WHITE); brand.setTypeface(null, Typeface.BOLD); root.addView(brand);
        root.addView(label("КАМЕРЫ В ЛОКАЛЬНОЙ СЕТИ", 11, green));
        TextView description = label("Hikvision SADP  /  ONVIF", 14, muted); description.setPadding(0, dp(10), 0, dp(14)); root.addView(description);
        LinearLayout summary = card(); count = label("0", 32, Color.WHITE); summary.addView(count);
        summary.addView(label("устройств обнаружено", 13, muted));
        status = label("Подключитесь к Wi-Fi сети камер", 13, green); status.setPadding(0, dp(12), 0, 0); summary.addView(status); root.addView(summary);
        LinearLayout actions = new LinearLayout(this);
        scan = button("Сканировать", this::scan); actions.addView(scan, weighted());
        actions.addView(button("+ IP", this::manual), new LinearLayout.LayoutParams(dp(88), dp(52))); root.addView(actions);
        LinearLayout secondary = new LinearLayout(this);
        secondary.addView(button("Учётные записи", this::editCredentials), weighted());
        connectAll = button("Снимки всех", () -> connect(new ArrayList<>(cameras.values()))); secondary.addView(connectAll, weighted()); root.addView(secondary);
        root.addView(label("УСТРОЙСТВА", 11, muted));
        ScrollView scroll = new ScrollView(this); list = column(); scroll.addView(list); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        render();
    }
    private int dp(int n) { return (int)(getResources().getDisplayMetrics().density * n + .5f); }
    private LinearLayout column() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout.LayoutParams weighted() { return new LinearLayout.LayoutParams(0, dp(52), 1); }
    private TextView label(String text, int size, int color) { TextView t = new TextView(this); t.setText(text); t.setTextSize(size); t.setTextColor(color); return t; }
    private Button button(String text, Runnable action) { Button b = new Button(this); b.setText(text); b.setTextSize(12); b.setAllCaps(false); b.setTextColor(bg); b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(green)); b.setOnClickListener(v -> action.run()); return b; }
    private LinearLayout card() {
        LinearLayout c = column(); c.setPadding(dp(16), dp(16), dp(16), dp(16));
        GradientDrawable shape = new GradientDrawable(); shape.setColor(panel); shape.setCornerRadius(dp(16)); c.setBackground(shape);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.setMargins(0, dp(10), 0, dp(10)); c.setLayoutParams(lp); return c;
    }
    private void ui(Runnable r) { runOnUiThread(() -> { if (!destroyed) r.run(); }); }
    private void busy(boolean value) { busy = value; connectAll.setEnabled(!value); scan.setText(discovery != null ? "Остановить" : "Сканировать"); }
    private void render() {
        count.setText(String.valueOf(cameras.size())); list.removeAllViews();
        if (cameras.isEmpty()) {
            LinearLayout empty = card(); empty.addView(label("Найдём ваши камеры", 20, Color.WHITE));
            empty.addView(label("Запустите поиск в сети. Если multicast отключён, добавьте IP камеры вручную.\n\nУчётные записи нужны только для информации и снимка.", 14, muted)); list.addView(empty); return;
        }
        for (Camera c : cameras.values()) {
            LinearLayout card = card();
            card.addView(label((c.hikvision ? "HIKVISION" : "КАМЕРА") + (c.onvif ? "   ·   ONVIF" : ""), 11, green));
            TextView title = label(c.model.isEmpty() ? "Модель пока неизвестна" : c.model, 19, Color.WHITE); title.setPadding(0, dp(8), 0, dp(8)); card.addView(title);
            TextView details = label("IP    " + c.ip + ":" + c.port + "\nMAC    " + (c.mac.isEmpty() ? "не получен" : c.mac)
                    + (c.serial.isEmpty() ? "" : "\nS/N    " + c.serial) + (c.firmware.isEmpty() ? "" : "\nFW    " + c.firmware), 13, muted);
            details.setTextIsSelectable(true); card.addView(details);
            if (!c.snapshotPath.isEmpty()) {
                ImageView image = new ImageView(this);
                BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(c.snapshotPath, options); options.inSampleSize = 1;
                while (options.outWidth / options.inSampleSize > 640 || options.outHeight / options.inSampleSize > 360) options.inSampleSize *= 2;
                options.inJustDecodeBounds = false;
                Bitmap bitmap = BitmapFactory.decodeFile(c.snapshotPath, options);
                image.setImageBitmap(bitmap); image.setScaleType(ImageView.ScaleType.FIT_CENTER);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(190)); lp.topMargin = dp(12); card.addView(image, lp);
                card.addView(label("Последний кадр: " + c.snapshotTime, 11, muted));
            }
            TextView state = label(c.status, 12, green); state.setPadding(0, dp(10), 0, dp(6)); card.addView(state);
            LinearLayout buttons = new LinearLayout(this);
            Button capture = button("Получить снимок", () -> connect(Collections.singletonList(c))); capture.setEnabled(!busy); buttons.addView(capture, weighted());
            if (!c.snapshotPath.isEmpty()) buttons.addView(button("В галерею", () -> saveGallery(c)), weighted());
            card.addView(buttons); list.addView(card);
        }
    }
    private void scan() {
        if (discovery != null) { discovery.cancel(); status.setText("Остановка поиска…"); return; }
        if (busy) { toast("Дождитесь получения снимков"); return; }
        Discovery session = new Discovery(); discovery = session;
        WifiManager wifi = (WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);
        try { multicastLock = wifi.createMulticastLock("HikCamScan"); multicastLock.setReferenceCounted(false); multicastLock.acquire(); }
        catch (Exception e) { discovery = null; toast("Не удалось включить multicast: " + CameraClient.message(e)); return; }
        busy(true); render(); status.setText("Поиск SADP и ONVIF · около 7 секунд…");
        worker.execute(() -> {
            String result;
            try {
                session.scan(c -> ui(() -> { Camera existing = cameras.get(c.ip); if (existing == null) cameras.put(c.ip, c); else existing.merge(c); render(); }));
                result = "Поиск завершён";
            } catch (Exception e) { result = CameraClient.message(e); }
            finally { releaseMulticast(); }
            String done = result;
            ui(() -> { discovery = null; busy(false); status.setText(done); render(); });
        });
    }
    private EditText input(String hint, boolean password) {
        EditText e = new EditText(this); e.setHint(hint); e.setTextColor(Color.WHITE); e.setHintTextColor(muted); e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | (password ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS));
        e.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS); return e;
    }
    private void manual() {
        if (busy) { toast("Дождитесь завершения операции"); return; }
        LinearLayout box = column(); box.setPadding(dp(20), 0, dp(20), 0);
        EditText ip = input("IP: 192.168.1.64", false), port = input("HTTP-порт: 80", false), onvifPort = input("ONVIF-порт (если отличается)", false);
        port.setText("80"); box.addView(ip); box.addView(port); box.addView(onvifPort);
        CheckBox onvif = new CheckBox(this); onvif.setText("Использовать ONVIF"); box.addView(onvif);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Добавить камеру").setView(box).setNegativeButton("Отмена", null).setPositiveButton("Добавить", null).create();
        dialog.setOnShowListener(v -> dialog.getButton(-1).setOnClickListener(w -> {
            String address = ip.getText().toString().trim();
            if (!Camera.privateIp(address)) { ip.setError("Укажите локальный IPv4 адрес"); return; }
            try {
                int p = Integer.parseInt(port.getText().toString()); if (p < 1 || p > 65535) throw new Exception();
                Camera c = cameras.get(address); if (c == null) c = new Camera(address);
                c.port = p; c.onvif = onvif.isChecked(); c.hikvision = !c.onvif;
                int op = onvifPort.getText().toString().trim().isEmpty() ? p : Integer.parseInt(onvifPort.getText().toString().trim());
                if (op < 1 || op > 65535) throw new Exception();
                c.deviceUrl = "http://" + address + ":" + op + "/onvif/device_service";
                cameras.put(address, c); dialog.dismiss(); render();
            } catch (Exception e) { port.setError("Порт от 1 до 65535"); }
        })); dialog.show();
    }
    private void editCredentials() {
        if (busy) { toast("Дождитесь завершения операции"); return; }
        LinearLayout box = column(); box.setPadding(dp(20), 0, dp(20), 0);
        box.addView(label("До 3 ваших учётных записей. Проверяются по порядку до первого успеха. Неверные пароли могут заблокировать вход на камере. Пароли хранятся только до закрытия приложения.", 13, muted));
        List<EditText> users = new ArrayList<>(), passwords = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            box.addView(label("Учётная запись " + (i + 1), 12, green));
            EditText user = input("Логин", false), password = input("Пароль", true);
            if (i < credentials.size()) { user.setText(credentials.get(i).user()); password.setText(credentials.get(i).password()); }
            users.add(user); passwords.add(password); box.addView(user); box.addView(password);
        }
        ScrollView scroll = new ScrollView(this); scroll.addView(box);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Учётные записи").setView(scroll).setNegativeButton("Отмена", null).setPositiveButton("Применить", null).create();
        dialog.setOnShowListener(v -> dialog.getButton(-1).setOnClickListener(w -> {
            List<CameraHttp.Credential> updated = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                String u = users.get(i).getText().toString().trim(), p = passwords.get(i).getText().toString();
                if (u.isEmpty() && p.isEmpty()) continue;
                if (u.isEmpty() || p.isEmpty()) { users.get(i).setError("Заполните логин и пароль"); return; }
                CameraHttp.Credential cr = new CameraHttp.Credential(u, p); if (!updated.contains(cr)) updated.add(cr);
            }
            credentials.clear(); credentials.addAll(updated); dialog.dismiss(); toast("Учётных записей: " + credentials.size());
        })); dialog.show();
    }
    private void connect(List<Camera> targets) {
        if (busy) { toast("Операция уже выполняется"); return; }
        if (targets.isEmpty()) { toast("Сначала найдите или добавьте камеры"); return; }
        if (credentials.isEmpty()) { editCredentials(); return; }
        List<CameraHttp.Credential> accounts = new ArrayList<>(credentials);
        new AlertDialog.Builder(this).setTitle("Получить снимки: " + targets.size() + "?")
                .setMessage("Подтверждаю, что управляю этими камерами и разрешаю проверку заданных учётных записей. После проверки повтор доступен через 5 минут.")
                .setNegativeButton("Отмена", null).setPositiveButton("Подключиться", (d, w) -> capture(targets, accounts)).show();
    }
    private void capture(List<Camera> targets, List<CameraHttp.Credential> accounts) {
        if (busy) return;
        busy(true); status.setText("Получение данных и снимков…"); render();
        worker.execute(() -> {
            for (Camera c : targets) {
                if (Thread.currentThread().isInterrupted()) break;
                if (SystemClock.elapsedRealtime() - c.lastAttempt < 300000 && c.lastAttempt != 0) {
                    ui(() -> { c.status = "Повторная проверка доступна через 5 минут после предыдущей"; render(); }); continue;
                }
                c.lastAttempt = SystemClock.elapsedRealtime();
                ui(() -> { c.status = "Подключение…"; render(); });
                String note; String path = "";
                try {
                    CameraClient.Result result = new CameraClient().connect(c, accounts); note = result.note();
                    if (result.image() != null) {
                        BitmapFactory.Options dimensions = new BitmapFactory.Options(); dimensions.inJustDecodeBounds = true;
                        BitmapFactory.decodeByteArray(result.image(), 0, result.image().length, dimensions);
                        if (dimensions.outWidth <= 0 || dimensions.outHeight <= 0 || (long)dimensions.outWidth * dimensions.outHeight > 40000000)
                            throw new IOException("Ответ камеры не является допустимым снимком");
                        File file = new File(getFilesDir(), "camera_" + c.ip + ".jpg");
                        try (FileOutputStream out = new FileOutputStream(file)) { out.write(result.image()); }
                        path = file.getAbsolutePath();
                    }
                } catch (Exception e) { note = CameraClient.message(e); }
                String finalNote = note, finalPath = path;
                ui(() -> { c.status = finalNote; if (!finalPath.isEmpty()) {
                    c.snapshotPath = finalPath;
                    c.snapshotTime = new java.text.SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()).format(new Date());
                } render(); });
            }
            accounts.clear(); ui(() -> { busy(false); status.setText("Обработка завершена"); render(); });
        });
    }
    private void saveGallery(Camera c) {
        Uri uri = null;
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, "HikCamScan_" + c.ip + "_" + System.currentTimeMillis() + ".jpg");
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/HikCamScan"); values.put(MediaStore.Images.Media.IS_PENDING, 1);
            uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IOException("Не удалось создать файл");
            try (InputStream in = new FileInputStream(c.snapshotPath); OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new IOException("Галерея недоступна");
                byte[] buffer = new byte[8192]; int n; while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            }
            values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0); getContentResolver().update(uri, values, null, null); toast("Сохранено в Pictures/HikCamScan");
        } catch (Exception e) { if (uri != null) getContentResolver().delete(uri, null, null); toast(CameraClient.message(e)); }
    }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
    private synchronized void releaseMulticast() { if (multicastLock != null && multicastLock.isHeld()) multicastLock.release(); }
    @Override protected void onDestroy() {
        destroyed = true; if (discovery != null) discovery.cancel(); releaseMulticast(); worker.shutdownNow(); credentials.clear(); super.onDestroy();
    }
}
