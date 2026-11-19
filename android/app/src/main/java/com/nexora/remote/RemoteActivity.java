package com.nexora.remote;

import android.app.Activity;
import android.app.AlertDialog;
import java.lang.ref.WeakReference;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public final class RemoteActivity extends Activity {
    private static final int MAX_URL_BYTES = 2048;
    private static final int SHORTCUT_PROTOCOL = 19;
    private static final int SUBMIT_TEXT_COMMAND = 22;
    private static final int SHORTCUT_COLUMNS = 8;
    private static final int SHORTCUT_ROWS = 3;
    private static final String[] BUILT_IN_LABELS = {
            "Oynat", "Sessiz", "Ses azalt", "Ses aç", "Parlaklık", "Linki Al", "Kilit", "PC’yi Kapat",
            "Geri", "İleri", "Kapat", "Yeni Sekme", "Esc", "Tam ekran", "Ekran görüntüsü", "Ses Çıkışı",
            "Masaüstü", "Pencereler", "İndirilenler", "WhatsApp", "YouTube", "Pencereyi Büyüt", "Yenile", "Sekmeyi Geri Aç"
    };
    private static final int DESKTOP_COMMAND = 7;
    private static final int LOCK_COMMAND = 13;
    private static final int SHUTDOWN_COMMAND = 16;
    private static final int[] BUILT_IN_COMMANDS = {
            12, 11, 27, 28, 39, 40, LOCK_COMMAND, SHUTDOWN_COMMAND,
            24, 33, 15, 32, 31, 25, 26, 34,
            DESKTOP_COMMAND, 6, 9, 8, 10, 36, 37, 38
    };
    private static final int BUILT_IN_COUNT = (int) java.util.Arrays.stream(BUILT_IN_COMMANDS)
            .filter(command -> command != 0).count();
    private static Session session;
    private EditText address;
    private Button connect;
    private TextView status;
    private AlertDialog confirmation;
    private int gestureFingers;
    private float lastX;
    private float lastY;
    private float travelX;
    private float travelY;
    private boolean shortcutSent;
    private boolean fingerLifted;
    private boolean twoFingerTap;
    private final float[] tapStartX = new float[2];
    private final float[] tapStartY = new float[2];
    private int remainingTapIndex;
    private Session dragSession;
    private boolean doubleTapPending;
    private float dragX;
    private float dragY;
    private int shortcutPage;
    private int selectedShortcut = -1;
    private boolean handlingVolumeUp;
    private boolean handlingVolumeDown;

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        if (key != KeyEvent.KEYCODE_VOLUME_UP && key != KeyEvent.KEYCODE_VOLUME_DOWN)
            return super.dispatchKeyEvent(event);

        boolean volumeUp = key == KeyEvent.KEYCODE_VOLUME_UP;
        boolean handled = volumeUp ? handlingVolumeUp : handlingVolumeDown;
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            handled = send(35, volumeUp ? 1 : -1, 0);
            if (volumeUp) handlingVolumeUp = handled;
            else handlingVolumeDown = handled;
        } else if (event.getAction() == KeyEvent.ACTION_UP) {
            if (volumeUp) handlingVolumeUp = false;
            else handlingVolumeDown = false;
        }
        return handled || super.dispatchKeyEvent(event);
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        float density = getResources().getDisplayMetrics().density;
        int padding = Math.round(8 * density);
        int verticalPadding = Math.round(3 * density);
        layout.setPadding(padding, verticalPadding, padding, verticalPadding);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
            getWindow().setNavigationBarColor(android.graphics.Color.TRANSPARENT);
            getWindow().setNavigationBarContrastEnforced(false);
            layout.setOnApplyWindowInsetsListener((view, insets) -> {
                var safe = insets.getInsets(WindowInsets.Type.statusBars()
                        | WindowInsets.Type.displayCutout() | WindowInsets.Type.tappableElement());
                var keyboard = insets.getInsets(WindowInsets.Type.ime());
                view.setPadding(padding + safe.left, verticalPadding + safe.top,
                        padding + safe.right, verticalPadding + Math.max(safe.bottom, keyboard.bottom));
                return insets;
            });
        }
        layout.setBackgroundColor(0xff0b1022);
        LinearLayout header = new LinearLayout(this);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        Button keyboard = shortcutButton("Klavye", 0xff26365b);
        header.addView(keyboard, new LinearLayout.LayoutParams(-2, -1));
        keyboard.setOnClickListener(view -> showKeyboard());
        Button connectionToggle = shortcutButton("Bağlantı ›", 0xff26365b);
        header.addView(connectionToggle, new LinearLayout.LayoutParams(-2, -1));
        status = new TextView(this);
        status.setText("Bağlantı kapalı.");
        status.setTextColor(0xffe6edff);
        status.setPadding(padding, 0, padding, 0);
        LinearLayout connectionRow = new LinearLayout(this);
        connectionRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        connectionRow.setVisibility(View.GONE);
        header.addView(connectionRow, new LinearLayout.LayoutParams(0, -1, 2));
        header.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
        connectionToggle.setOnClickListener(view -> {
            boolean expanded = connectionRow.getVisibility() == View.VISIBLE;
            connectionRow.setVisibility(expanded ? View.GONE : View.VISIBLE);
            connectionToggle.setText(expanded ? "Bağlantı ›" : "Bağlantı ‹");
        });
        address = new EditText(this);
        address.setHint("Windows IP");
        address.setTextColor(0xffffffff);
        address.setHintTextColor(0xffd8d0ff);
        address.setTextSize(14);
        address.setSingleLine();
        connectionRow.addView(address, new LinearLayout.LayoutParams(0, -2, 1));
        connect = new Button(this);
        connect.setText("Bağlan");
        connect.setTextSize(12);
        connect.setAllCaps(false);
        connect.setMinimumWidth(0);
        connect.setMinWidth(0);
        connect.setPadding(padding, 0, padding, 0);
        styleButton(connect, 0xff00776c);
        connectionRow.addView(connect);
        Button usb = new Button(this);
        usb.setText("USB");
        usb.setTextSize(12);
        usb.setMinimumWidth(0);
        usb.setMinWidth(0);
        usb.setPadding(padding, 0, padding, 0);
        styleButton(usb, 0xff005ce6);
        connectionRow.addView(usb);
        usb.setOnClickListener(view -> {
            if (session == null) startConnection("127.0.0.1", "USB");
        });
        LinearLayout shortcutGrid = new LinearLayout(this);
        shortcutGrid.setOrientation(LinearLayout.VERTICAL);
        layout.addView(shortcutGrid);
        JSONArray saved = loadShortcuts();
        Button page = shortcutButton("›", 0xff26365b);
        page.setContentDescription("Sonraki kısayol sayfası");
        header.addView(page, new LinearLayout.LayoutParams(-2, -1));
        page.setOnClickListener(view -> {
            shortcutPage = (shortcutPage + 1) % shortcutPageCount(saved);
            showShortcuts(shortcutGrid, page, saved);
        });
        showShortcuts(shortcutGrid, page, saved);
        View pad = new View(this);
        pad.setContentDescription("Touchpad");
        GradientDrawable padBackground = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] {0xff163b83, 0xff4a2280});
        padBackground.setCornerRadius(16 * density);
        pad.setBackground(padBackground);
        layout.addView(pad, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout.LayoutParams headerParams = new LinearLayout.LayoutParams(-1, Math.round(40 * density));
        headerParams.topMargin = Math.round(2 * density);
        layout.addView(header, headerParams);
        setContentView(layout);
        widgetHost = new WeakReference<>(this);
        RemoteWidgetProvider.refresh(this);
        if (session != null && session.ready) {
            connect.setText("Kes");
            status.setText("Bağlı · " + session.transport);
        }
        if (state == null) openSharedLink(getIntent());

        connect.setOnClickListener(view -> {
            if (session != null) {
                end(session, "Bağlantı kapalı.");
            } else {
                String host = address.getText().toString().trim();
                if (host.isEmpty()) {
                    address.setError("Host ekranındaki IP adresini girin.");
                    return;
                }
                startConnection(host, "Wi-Fi");
            }
        });

        GestureDetector gestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent event) { return true; }
            @Override public boolean onSingleTapConfirmed(MotionEvent event) { return send(2, 0, 0); }
            @Override public boolean onDoubleTapEvent(MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    doubleTapPending = true;
                    dragX = event.getX();
                    dragY = event.getY();
                }
                return true;
            }
            @Override public boolean onScroll(MotionEvent start, MotionEvent current, float dx, float dy) {
                return current.getPointerCount() == 1 && send(1, -dx, -dy);
            }
        });
        pad.setOnTouchListener((view, event) -> handleTouch(gestures, event, density));
    }

    private void showKeyboard() {
        Session active = session;
        if (active == null || !active.ready) {
            status.setText("Klavye için önce bağlanın.");
            return;
        }

        float density = getResources().getDisplayMetrics().density;
        int gap = Math.round(8 * density);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(gap, gap, gap, gap);
        row.setBackgroundColor(0xff0b1022);
        EditText input = new EditText(this);
        input.setHint("Windows’ta odaktaki alana yaz");
        input.setTextColor(0xfff3f6ff);
        input.setHintTextColor(0xffb9c7e9);
        input.setBackgroundTintList(ColorStateList.valueOf(0xff8e78ee));
        input.setSingleLine(true);
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEND
                | android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        row.addView(input, new LinearLayout.LayoutParams(0, -2, 1));
        Button send = shortcutButton("Gönder", 0xff5841b2);
        Button close = shortcutButton("Kapat", 0xff26365b);
        for (Button button : new Button[] {send, close}) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, Math.round(44 * density));
            params.setMarginStart(gap);
            row.addView(button, params);
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(row)
                .create();
        var window = dialog.getWindow();
        window.setGravity(android.view.Gravity.TOP);
        window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        close.setOnClickListener(view -> dialog.dismiss());
        dialog.setOnShowListener(ignored -> {
            Runnable submit = () -> {
                byte[] text = input.getText().toString().getBytes(StandardCharsets.UTF_8);
                if (text.length == 0) return;
                if (text.length > 2048) {
                    input.setError("Metin çok uzun. Daha kısa parçalar hâlinde gönderin.");
                } else if (session != active || !enqueue(payloadPacket(SUBMIT_TEXT_COMMAND, text))) {
                    input.setError("Metin gönderilemedi. Bağlantıyı kontrol edin.");
                } else {
                    input.setText("");
                }
            };
            send.setOnClickListener(view -> submit.run());
            input.setOnEditorActionListener((view, action, event) -> {
                if (action != android.view.inputmethod.EditorInfo.IME_ACTION_SEND) return false;
                submit.run();
                return true;
            });
            input.requestFocus();
            input.post(() -> getSystemService(android.view.inputmethod.InputMethodManager.class)
                    .showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT));
        });
        dialog.show();
        window.setLayout(-1, -2);
    }

    @Override protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        openSharedLink(intent);
    }

    private void openSharedLink(android.content.Intent intent) {
        if (!android.content.Intent.ACTION_SEND.equals(intent.getAction())) return;
        CharSequence text = intent.getCharSequenceExtra(android.content.Intent.EXTRA_TEXT);
        android.content.ClipData clip = intent.getClipData();
        String url = sharedWebUrl(text);
        for (int i = 0; url == null && clip != null && i < clip.getItemCount(); i++) {
            android.content.ClipData.Item item = clip.getItemAt(i);
            url = sharedWebUrl(item.getText());
            if (url == null && item.getUri() != null) url = sharedWebUrl(item.getUri().toString());
        }
        if (url == null) {
            status.setText("Paylaşılan içerikte geçerli bir web bağlantısı bulunamadı.");
        } else if (session == null || !session.ready) {
            status.setText("Bilgisayar bağlantısı kapalı. Bağlanıp tekrar paylaşın.");
        } else {
            status.setText(enqueue(webShortcutPacket(url))
                    ? "Bağlantı gönderim sırasına alındı." : "Gönderim sırası dolu. Tekrar deneyin.");
        }
    }

    private static String sharedWebUrl(CharSequence text) {
        if (text == null) return null;
        var links = java.util.regex.Pattern.compile("https?://[^\\s<>\"\\p{Cntrl}]+",
                java.util.regex.Pattern.CASE_INSENSITIVE).matcher(text);
        while (links.find()) {
            String url = links.group();
            if (isWebUrl(url)) return url;
        }
        return null;
    }
    static String widgetConnectionStatus() {
        Session active = session;
        return active == null || !active.ready ? "Bağlantı yok" : active.transport + " bağlı";
    }

    private void startConnection(String host, String transport) {
        startForegroundService(new android.content.Intent(this, ConnectionService.class));
        session = new Session(getApplicationContext(), host, transport);
        connect.setText("Kes");
        status.setText(transport + " üzerinden bağlanıyor…");
        new Thread(session, "nexora-connection").start();
    }

    private boolean handleTouch(GestureDetector gestures, MotionEvent event, float density) {
        int action = event.getActionMasked();
        int count = event.getPointerCount();
        if (action == MotionEvent.ACTION_DOWN) {
            releaseDrag();
            doubleTapPending = false;
            gestureFingers = 1;
            fingerLifted = false;
            twoFingerTap = false;
        }
        if (doubleTapPending) {
            if (action == MotionEvent.ACTION_MOVE) {
                float dx = event.getX() - dragX;
                float dy = event.getY() - dragY;
                int slop = ViewConfiguration.get(this).getScaledTouchSlop();
                if (dx * dx + dy * dy > slop * slop) {
                    doubleTapPending = false;
                    if (send(19, 0, 0)) dragSession = session;
                }
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
                    || action == MotionEvent.ACTION_POINTER_DOWN) {
                if (action == MotionEvent.ACTION_UP) send(21, 0, 0);
                doubleTapPending = false;
            }
        }
        if (dragSession != null) {
            if (action == MotionEvent.ACTION_MOVE && count == 1 && dragSession == session) {
                send(1, event.getX() - dragX, event.getY() - dragY);
                dragX = event.getX();
                dragY = event.getY();
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
                    || action == MotionEvent.ACTION_POINTER_DOWN) {
                releaseDrag();
            }
        }
        if (gestureFingers == 1 && action != MotionEvent.ACTION_POINTER_DOWN) {
            return gestures.onTouchEvent(event);
        }
        int slop = ViewConfiguration.get(this).getScaledTouchSlop();
        if (gestureFingers == 2 && action == MotionEvent.ACTION_POINTER_UP && count == 2) {
            checkTapMovement(event, slop);
            remainingTapIndex = 1 - event.getActionIndex();
        }
        if (action == MotionEvent.ACTION_POINTER_UP) fingerLifted = true;
        if (fingerLifted && twoFingerTap) {
            if (count != 1 && action == MotionEvent.ACTION_POINTER_DOWN) twoFingerTap = false;
            if (count == 1 && outsideTapSlop(event, 0, remainingTapIndex, slop)) twoFingerTap = false;
            if (twoFingerTap && action == MotionEvent.ACTION_UP
                    && event.getEventTime() - event.getDownTime() <= ViewConfiguration.getDoubleTapTimeout()) {
                send(3, 0, 0);
            }
        }
        if (fingerLifted) return true;

        if (action == MotionEvent.ACTION_POINTER_DOWN && count > gestureFingers) {
            if (gestureFingers == 1) {
                MotionEvent cancel = MotionEvent.obtain(event);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                gestures.onTouchEvent(cancel);
                cancel.recycle();
            }
            gestureFingers = count;
            twoFingerTap = count == 2;
            if (twoFingerTap) {
                for (int index = 0; index < 2; index++) {
                    tapStartX[index] = event.getX(index);
                    tapStartY[index] = event.getY(index);
                }
            }
            lastX = center(event, true);
            lastY = center(event, false);
            travelX = travelY = 0;
            shortcutSent = false;
        } else if (action == MotionEvent.ACTION_MOVE && count == gestureFingers) {
            float x = center(event, true);
            float y = center(event, false);
            travelX += x - lastX;
            travelY += y - lastY;
            lastX = x;
            lastY = y;
            if (count == 2) {
                checkTapMovement(event, slop);
                float step = 24 * density;
                int ticks = Math.max(-127, Math.min(127, (int) (travelY / step)));
                if (ticks != 0 && send(4, ticks, 0)) {
                    twoFingerTap = false;
                    travelY -= ticks * step;
                }
            } else if (count == 3 && !shortcutSent) {
                float threshold = 64 * density;
                if (Math.abs(travelX) >= threshold && Math.abs(travelX) > Math.abs(travelY)) {
                    shortcutSent = send(5, travelX > 0 ? 1 : -1, 0);
                } else if (travelY <= -threshold && -travelY > Math.abs(travelX)) {
                    shortcutSent = send(6, 0, 0);
                }
            }
        }
        return true;
    }

    private void checkTapMovement(MotionEvent event, int slop) {
        if (outsideTapSlop(event, 0, 0, slop) || outsideTapSlop(event, 1, 1, slop)) twoFingerTap = false;
    }

    private boolean outsideTapSlop(MotionEvent event, int pointer, int start, int slop) {
        float dx = event.getX(pointer) - tapStartX[start];
        float dy = event.getY(pointer) - tapStartY[start];
        return dx * dx + dy * dy > slop * slop;
    }

    private void releaseDrag() {
        Session active = dragSession;
        dragSession = null;
        if (active != null && active == session && !send(20, 0, 0)) {
            end(active, "Sürükleme sonlandırıldı. Tekrar bağlanın.");
        }
    }

    private static float center(MotionEvent event, boolean horizontal) {
        float total = 0;
        for (int index = 0; index < event.getPointerCount(); index++) {
            total += horizontal ? event.getX(index) : event.getY(index);
        }
        return total / event.getPointerCount();
    }

    private void styleButton(Button button, int color) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(12 * getResources().getDisplayMetrics().density);
        button.setTextColor(0xffffffff);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x55ffffff), shape, null));
    }

    private Button shortcutButton(String label, int color) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setSingleLine();
        button.setEllipsize(android.text.TextUtils.TruncateAt.END);
        button.setTextSize(12);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        styleButton(button, color);
        int padding = Math.round(6 * getResources().getDisplayMetrics().density);
        button.setPadding(padding, 0, padding, 0);
        return button;
    }

    private int shortcutPageCount(JSONArray saved) {
        int pageSize = SHORTCUT_ROWS * SHORTCUT_COLUMNS;
        return (BUILT_IN_COUNT + saved.length() + pageSize - 1) / pageSize;
    }

    private void showShortcuts(LinearLayout grid, Button page, JSONArray saved) {
        grid.removeAllViews();
        int pages = shortcutPageCount(saved);
        page.setText((shortcutPage + 1) + "/" + pages + " ›");
        page.setVisibility(pages > 1 ? View.VISIBLE : View.GONE);
        float density = getResources().getDisplayMetrics().density;
        int gap = Math.round(4 * density);
        int slot = shortcutPage * SHORTCUT_ROWS * SHORTCUT_COLUMNS;

        for (int line = 0; line < SHORTCUT_ROWS; line++) {
            LinearLayout row = new LinearLayout(this);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, Math.round(38 * density));
            rowParams.topMargin = Math.round(2 * density);
            if (line == SHORTCUT_ROWS - 1) rowParams.bottomMargin = Math.round(2 * density);
            grid.addView(row, rowParams);
            for (int column = 0; column < SHORTCUT_COLUMNS; column++, slot++) {
                int position = slot;
                int source = getPreferences(MODE_PRIVATE).getInt("shortcut_slot_" + position, position);
                int command = source < BUILT_IN_COMMANDS.length ? BUILT_IN_COMMANDS[source] : 0;
                JSONObject item = command == 0 ? saved.optJSONObject(source - BUILT_IN_COUNT) : null;
                Button button = createShortcutSlot(source, command, item);
                button.setEnabled(true);
                button.setAlpha(position == selectedShortcut ? 0.5f : 1f);
                button.setOnLongClickListener(view -> {
                    if (selectedShortcut >= 0 && selectedShortcut != position) {
                        var preferences = getPreferences(MODE_PRIVATE);
                        int first = preferences.getInt("shortcut_slot_" + selectedShortcut, selectedShortcut);
                        preferences.edit().putInt("shortcut_slot_" + selectedShortcut, source)
                                .putInt("shortcut_slot_" + position, first).apply();
                        selectedShortcut = -1;
                        android.widget.Toast.makeText(this, "Düğmelerin yerleri değişti.", android.widget.Toast.LENGTH_SHORT).show();
                    } else if (selectedShortcut == position) {
                        selectedShortcut = -1;
                    } else {
                        selectedShortcut = position;
                        android.widget.Toast.makeText(this, "Diğer düğmeye de basılı tutun.", android.widget.Toast.LENGTH_SHORT).show();
                    }
                    showShortcuts(grid, page, saved);
                    return true;
                });
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -1, 1);
                if (column > 0) params.setMarginStart(gap);
                row.addView(button, params);
            }
        }
    }

    private Button createShortcutSlot(int slot, int command, JSONObject item) {
        if (command != 0) {
            Button button = shortcutButton(BUILT_IN_LABELS[slot], 0xff5841b2);
            button.setSingleLine(false);
            button.setMaxLines(2);
            button.setOnClickListener(view -> {
                if (command == SHUTDOWN_COMMAND) confirmShutdown();
                else if (command == 39) showBrightnessSlider();
                else if (!send(command, 0, 0) && command == 40)
                    status.setText("Bağlantıyı almak için bilgisayara bağlanın.");
            });
            return button;
        }
        if (item != null && isWebUrl(item.optString("url"))) {
            Button button = shortcutButton(item.optString("name"), 0xff12665f);
            byte[] packet = webShortcutPacket(item.optString("url"));
            button.setOnClickListener(view -> enqueue(packet));
            return button;
        }
        Button blank = shortcutButton("", 0xff28334e);
        blank.setEnabled(false);
        blank.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return blank;
    }

    private static WeakReference<RemoteActivity> widgetHost = new WeakReference<>(null);
    private static Session shutdownSession;
    private static long shutdownDeadline;

    static String runWidgetCommand(String action) {
        if (session == null || !session.ready)
            return "Önce Uzam üzerinden bilgisayara bağlanın.";
        int command;
        switch (action) {
            case RemoteWidgetProvider.DESKTOP -> command = DESKTOP_COMMAND;
            case RemoteWidgetProvider.LOCK -> command = LOCK_COMMAND;
            case RemoteWidgetProvider.SHUTDOWN -> {
                long now = android.os.SystemClock.elapsedRealtime();
                if (shutdownSession != session || now >= shutdownDeadline) {
                    shutdownSession = session;
                    shutdownDeadline = now + 5000;
                    return "Kapatmak için 5 saniye içinde tekrar dokunun.";
                }
                command = SHUTDOWN_COMMAND;
            }
            default -> { return null; }
        }
        shutdownSession = null;
        return session.commands.offer(new byte[] {(byte) command, 0, 0}) ? null : "Komut gönderilemedi.";
    }
    private void showReceivedLink(String url) {
        if (!isWebUrl(url)) {
            status.setText("Bağlantı alınamadı. Bilgisayarda bir web sayfasını öne getirin.");
            return;
        }
        new AlertDialog.Builder(this).setTitle("Bilgisayardan gelen bağlantı")
                .setMessage(url).setNegativeButton("Vazgeç", null)
                .setPositiveButton("Telefonda Aç", (dialog, button) -> {
                    try {
                        startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(url)).addCategory(android.content.Intent.CATEGORY_BROWSABLE));
                    } catch (android.content.ActivityNotFoundException error) {
                        status.setText("Bağlantıyı açacak tarayıcı bulunamadı.");
                    }
                }).show();
    }

    private void showBrightnessSlider() {
        var slider = new android.widget.SeekBar(this);
        slider.setMax(100);
        var label = new TextView(this);
        var panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(24 * getResources().getDisplayMetrics().density);
        panel.setPadding(padding, 0, padding, 0);
        panel.addView(label);
        panel.addView(slider);
        int previous = getPreferences(MODE_PRIVATE).getInt("brightness_target", 50);
        slider.setProgress(previous);
        label.setText("Hedef: %" + previous);
        slider.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar bar, int value, boolean fromUser) {
                label.setText("Hedef: %" + value);
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar bar) {}
            @Override public void onStopTrackingTouch(android.widget.SeekBar bar) {}
        });
        new AlertDialog.Builder(this).setTitle("Ekran parlaklığı").setView(panel)
                .setNegativeButton("Vazgeç", null)
                .setPositiveButton("Uygula", (dialog, button) -> {
                    int value = slider.getProgress();
                    if (send(39, value, 0)) {
                        getPreferences(MODE_PRIVATE).edit().putInt("brightness_target", value).apply();
                    } else {
                        status.setText("Parlaklığı ayarlamak için bilgisayara bağlanın.");
                    }
                }).show();
    }

    private void confirmShutdown() {
        Session active = session;
        if (active == null || !active.ready) {
            status.setText("Bilgisayarı kapatmak için önce bağlanın.");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Bilgisayar kapatılsın mı?")
                .setMessage("Açık belgelerinizi kaydettiğinizden emin olun.")
                .setNegativeButton("Vazgeç", null)
                .setPositiveButton("Kapat", (dialog, which) -> {
                    if (session == active && send(SHUTDOWN_COMMAND, 0, 0)) {
                        status.setText("Kapatma komutu gönderildi.");
                    } else {
                        status.setText("Kapatma komutu gönderilemedi.");
                    }
                })
                .show();
    }

    private JSONArray loadShortcuts() {
        String json = getPreferences(MODE_PRIVATE).getString("web_shortcuts", "[]");
        try {
            return new JSONArray(json);
        } catch (JSONException ignored) {
            return new JSONArray();
        }
    }

    private static boolean isWebUrl(String url) {
        if (url.getBytes(StandardCharsets.UTF_8).length > MAX_URL_BYTES) return false;
        try {
            URI uri = URI.create(url);
            return uri.getHost() != null && uri.getRawUserInfo() == null
                    && ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()));
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    private static byte[] webShortcutPacket(String url) {
        return payloadPacket(14, url.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] payloadPacket(int command, byte[] payload) {
        byte[] packet = new byte[payload.length + 3];
        packet[0] = (byte) command;
        packet[1] = (byte) (payload.length >>> 8);
        packet[2] = (byte) payload.length;
        System.arraycopy(payload, 0, packet, 3, payload.length);
        return packet;
    }

    private boolean send(int command, float dx, float dy) {
        return enqueue(new byte[] {(byte) command, motion(dx), motion(dy)});
    }

    private boolean enqueue(byte[] packet) {
        Session active = session;
        if (active == null || !active.ready) return false;
        return active.commands.offer(packet);
    }

    private static byte motion(float delta) {
        return (byte) Math.max(-127, Math.min(127, Math.round(delta)));
    }

    private void end(Session active, String message) {
        if (session != active) return;
        session = null;
        shutdownSession = null;
        RemoteWidgetProvider.refresh(this);
        if (confirmation != null) {
            confirmation.dismiss();
            confirmation = null;
        }
        active.close();
        stopService(new android.content.Intent(this, ConnectionService.class));
        connect.setText("Bağlan");
        status.setText(message);
    }

    @Override protected void onDestroy() {
        if (widgetHost.get() == this) widgetHost.clear();
        if (session != null && !session.ready && widgetHost.get() == null) end(session, "");
        super.onDestroy();
    }

    private static final class Session implements Runnable {
        private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        private final android.content.Context context;
        final String host;
        final String transport;
        final ArrayBlockingQueue<byte[]> commands = new ArrayBlockingQueue<>(48);
        final ArrayBlockingQueue<Boolean> decision = new ArrayBlockingQueue<>(1);
        volatile boolean ready;
        private volatile boolean closed;
        private volatile SSLSocket socket;
        private volatile Thread worker;

        Session(android.content.Context context, String host, String transport) {
            this.context = context;
            this.host = host;
            this.transport = transport;
        }

        void close() {
            closed = true;
            Thread thread = worker;
            if (thread != null) thread.interrupt();
            SSLSocket connection = socket;
            if (connection != null) {
                try { connection.close(); } catch (IOException ignored) {}
            }
        }

        @Override public void run() {
            worker = Thread.currentThread();
            String result = "Bağlantı sona erdi.";
            try {
                SSLContext tlsContext = SSLContext.getInstance("TLS");
                // The provisional certificate is verified by comparing its fingerprint on both screens.
                tlsContext.init(null, new TrustManager[] {new X509TrustManager() {
                    @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    @Override public void checkClientTrusted(X509Certificate[] chain, String type) {
                        throw new UnsupportedOperationException();
                    }
                    @Override public void checkServerTrusted(X509Certificate[] chain, String type)
                            throws java.security.cert.CertificateException {
                        if (chain == null || chain.length != 1) {
                            throw new java.security.cert.CertificateException("Beklenmeyen sertifika zinciri.");
                        }
                        chain[0].checkValidity();
                    }
                }}, null);
                try (SSLSocket connection = (SSLSocket) tlsContext.getSocketFactory().createSocket()) {
                    socket = connection;
                    if (closed) return;
                    connection.connect(new InetSocketAddress(host, 47832), 5000);
                    connection.setSoTimeout(60000);
                    connection.setTcpNoDelay(true);
                    connection.startHandshake();
                    byte[] digest = MessageDigest.getInstance("SHA-256")
                            .digest(connection.getSession().getPeerCertificates()[0].getEncoded());
                    StringBuilder code = new StringBuilder();
                    for (int index = 0; index < 12; index++) {
                        code.append(String.format(Locale.ROOT, "%02X", digest[index] & 255));
                    }
                    var output = connection.getOutputStream();
                    output.write(SHORTCUT_PROTOCOL);
                    int version;
                    try {
                        version = connection.getInputStream().read();
                    } catch (javax.net.ssl.SSLException error) {
                        result = "Sürüm doğrulanamadı. Windows’ta güncel Uzam uygulamasını açın.";
                        return;
                    }
                    if (version != SHORTCUT_PROTOCOL) {
                        result = "Windows sürümü eski. Bilgisayarda güncel Uzam uygulamasını açın.";
                        return;
                    }
                    main.post(() -> {
                        RemoteActivity activity = widgetHost.get();
                        if (session != this || activity == null) { decision.offer(false); return; }
                        activity.confirmation = new AlertDialog.Builder(activity)
                                .setTitle("Eşleştirme kodu")
                                .setMessage(code + "\n\nWindows ekranındaki kodla karşılaştırın.")
                                .setPositiveButton("Kod aynı", (dialog, which) -> decision.offer(true))
                                .setNegativeButton("İptal", (dialog, which) -> decision.offer(false))
                                .setOnCancelListener(dialog -> decision.offer(false))
                                .show();
                    });
                    if (!Boolean.TRUE.equals(decision.poll(60, java.util.concurrent.TimeUnit.SECONDS))
                            || connection.getInputStream().read() != 1) {
                        result = "Eşleştirme tamamlanmadı.";
                        return;
                    }
                    ready = true;
                    main.post(() -> {
                        if (session == this) {
                            RemoteActivity activity = widgetHost.get();
                            if (activity != null) activity.status.setText("Bağlı · " + transport);
                            RemoteWidgetProvider.refresh(context);
                        }
                    });
                    connection.setSoTimeout(0);
                    Thread reader = new Thread(() -> {
                        try {
                            var input = new java.io.DataInputStream(connection.getInputStream());
                            int response;
                            while ((response = input.read()) != -1) {
                                if (response != 40) throw new IOException("Bilinmeyen Windows yanıtı.");
                                int size = input.readUnsignedShort();
                                if (size > MAX_URL_BYTES) throw new IOException("Bağlantı adresi çok uzun.");
                                byte[] payload = new byte[size];
                                input.readFully(payload);
                                String url = new String(payload, StandardCharsets.UTF_8);
                                main.post(() -> {
                                    RemoteActivity activity = widgetHost.get();
                                    if (session == this && activity != null) activity.showReceivedLink(url);
                                });
                            }
                        }
                        catch (IOException ignored) {}
                        finally { worker.interrupt(); }
                    }, "nexora-disconnect");
                    reader.start();
                    while (!closed) output.write(commands.take());
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } catch (Exception error) {
                result = "USB".equals(transport) && error instanceof java.net.ConnectException
                        ? "USB bağlantısı kurulamadı. Windows’ta USB’yi hazırla ve Dinlemeyi başlat’a basın."
                        : "Bağlantı hatası · " + error.getMessage();
            } finally {
                String message = result;
                main.post(() -> {
                    if (session != this) return;
                    RemoteActivity activity = widgetHost.get();
                    if (activity != null) {
                        activity.end(this, message);
                    } else {
                        session = null;
                        context.stopService(new android.content.Intent(context, ConnectionService.class));
                        RemoteWidgetProvider.refresh(context);
                    }
                });
            }
        }
    }
}
