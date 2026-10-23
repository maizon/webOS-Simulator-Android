package io.maizon.webossim;

import android.annotation.SuppressLint;
import android.app.*;
import android.content.*;
import android.content.pm.ApplicationInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.*;
import android.os.*;
import android.provider.DocumentsContract;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * IPK launcher for Android. Not LG firmware: app-level webOS compatibility with transparent mocks.
 * No native .so execution. Installing a third-party IPK still executes its web JavaScript.
 */
public class MainActivity extends Activity {
    private static final int PICK_IPK = 1001, PICK_WEB_FILE = 1002;
    private static final int BG = 0xff10131c, PANEL = 0xff1c2330, TEXT = 0xffedf1fa;
    private static final int SUB = 0xffadb9cc, ACCENT = 0xff63dfbe;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Deque<String> events = new ArrayDeque<>();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private File appStorage;
    private JSONObject profile;
    private LocalWebServer server;
    private int localPort;
    private WebView webView;
    private WebChromeClient.FileChooserParams pendingParams;
    private ValueCallback<Uri[]> webFiles;
    private LinearLayout main;
    private IpkInstaller.InstalledApp currentApp;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        appStorage = new File(getFilesDir(), "installed-apps");
        if (!appStorage.isDirectory()) appStorage.mkdirs();
        try (InputStream in = getAssets().open("uq8050psb.json")) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] temp = new byte[8192]; int n;
            while ((n = in.read(temp)) != -1) buf.write(temp, 0, n);
            profile = new JSONObject(buf.toString("UTF-8"));
        } catch (Exception e) {
            profile = new JSONObject(); record("ERROR", "Falha no perfil: " + e);
        }
        main = new LinearLayout(this);
        main.setOrientation(LinearLayout.VERTICAL);
        main.setBackgroundColor(BG);
        setContentView(main);
        launchServer();
        showLibrary();
    }

    private void launchServer() { launchServer(null); }

    private void launchServer(String appId) {
        try {
            if (server != null) server.stop();
            boolean isolate = getPreferences(0).getBoolean("isolation", true);
            server = new LocalWebServer(this, appStorage, isolate);
            localPort = server.start(appId);
            record("INFO", "Servidor HTTP em 127.0.0.1:" + localPort + "; COOP/COEP=" + isolate);
            if (localPort < 17822) record("WARN", "Porta alternativa: IndexedDB pode mudar de origem");
        } catch (Exception e) {
            localPort = 0;
            record("ERROR", "Falha ao abrir servidor local: " + e);
        }
    }

    private int dp(float n) { return (int) (n * getResources().getDisplayMetrics().density + .5f); }
    private GradientDrawable background(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color); d.setCornerRadius(dp(radius)); return d;
    }
    private TextView text(String label, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(label); t.setTextSize(sp); t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }
    private TextView button(String label, Runnable click) {
        TextView b = text(label, 15, BG, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(15), dp(12), dp(15), dp(12));
        b.setBackground(background(ACCENT, 13));
        b.setOnClickListener(v -> click.run());
        return b;
    }
    private LinearLayout column(int pad) {
        LinearLayout c = new LinearLayout(this);
        c.setPadding(dp(pad), dp(pad), dp(pad), dp(pad));
        c.setOrientation(LinearLayout.VERTICAL);
        return c;
    }
    private void showLibrary() {
        currentApp = null;
        if (webView != null) {
            webView.stopLoading(); webView.destroy(); webView = null;
        }
        main.removeAllViews();
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout body = column(20);
        scroll.addView(body);
        main.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        TextView heading = text("webOS 22  •  SIMULATOR", 23, TEXT, true);
        body.addView(heading);
        TextView description = text("LG UQ8050PSB · runtime de compatibilidade IPK (não é firmware LG)", 13, SUB, false);
        description.setPadding(0, dp(10), 0, dp(19)); body.addView(description);
        TextView install = button("＋  Instalar arquivo .IPK", this::pickIpk);
        body.addView(install, new LinearLayout.LayoutParams(-1, dp(55)));

        LinearLayout actions = new LinearLayout(this);
        actions.setPadding(0, dp(13), 0, dp(15));
        actions.setOrientation(LinearLayout.HORIZONTAL);
        body.addView(actions);
        TextView settings = secondaryButton("⚙ Configurações", this::showSettings);
        actions.addView(settings, new LinearLayout.LayoutParams(0, dp(48), 1));
        TextView logs = secondaryButton("☷ Logs", this::showLogs);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(48), 1);
        lp.leftMargin = dp(10); actions.addView(logs, lp);

        TextView section = text("APLICATIVOS INSTALADOS", 14, SUB, true);
        section.setPadding(0, dp(8), 0, dp(10)); body.addView(section);
        List<IpkInstaller.InstalledApp> apps = IpkInstaller.installed(appStorage);
        if (apps.isEmpty()) {
            TextView empty = text("Nenhum IPK instalado. Escolha um app webOS com HTML5/JS/WASM para começar.", 15, SUB, false);
            empty.setPadding(dp(18), dp(28), dp(18), dp(28));
            empty.setBackground(background(PANEL, 14));
            body.addView(empty, new LinearLayout.LayoutParams(-1, -2));
        }
        for (IpkInstaller.InstalledApp app : apps) {
            LinearLayout card = column(15);
            card.setBackground(background(PANEL, 12));
            TextView title = text("▣  " + app.title, 17, TEXT, true);
            card.addView(title);
            TextView detail = text(app.id + "  ·  v" + app.version + "   ·  HTML5/IPK", 12, SUB, false);
            detail.setPadding(0, dp(7), 0, dp(7)); card.addView(detail);
            TextView hint = text("Toque para iniciar · Segure para desinstalar", 12, ACCENT, false);
            card.addView(hint);
            card.setOnClickListener(v -> startApp(app));
            card.setOnLongClickListener(v -> {
                new AlertDialog.Builder(this)
                    .setTitle("Desinstalar " + app.title + "?")
                    .setMessage("Arquivos e dados salvos pela origem web podem continuar no WebView.")
                    .setNegativeButton("Cancelar", null)
                    .setPositiveButton("Desinstalar", (d, which) -> {
                        IpkInstaller.deleteTree(app.directory); record("INFO", "Desinstalado: " + app.id); showLibrary();
                    }).show();
                return true;
            });
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2);
            cl.bottomMargin = dp(10); body.addView(card, cl);
        }
        TextView note = text("STATUS: APIs Luna de sistema usam MOCKS; WebGL/WASM/rede dependem do Android WebView. DRM e .so nativos não são executados.", 12, SUB, false);
        note.setPadding(0, dp(13), 0, 0); body.addView(note);
    }

    private TextView secondaryButton(String label, Runnable handler) {
        TextView b = text(label, 14, TEXT, true);
        b.setGravity(Gravity.CENTER);
        b.setBackground(background(PANEL, 12));
        b.setOnClickListener(v -> handler.run());
        return b;
    }

    private void pickIpk() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, PICK_IPK);
    }

    private void showSettings() {
        boolean isolation = getPreferences(0).getBoolean("isolation", true);
        boolean fakeAgent = getPreferences(0).getBoolean("fakeua", true);
        String[] opts = {"COOP/COEP para WASM threads (reinicia servidor)", "User-Agent compatível webOS 22"};
        boolean[] selected = {isolation, fakeAgent};
        new AlertDialog.Builder(this)
            .setTitle("LG UQ8050PSB · compatibilidade")
            .setMultiChoiceItems(opts, selected, (d, i, checked) -> selected[i] = checked)
            .setMessage("A resolução 1920×1080 é lógica no perfil. Android WebView não reproduz a GPU/firmware da TV.")
            .setPositiveButton("Salvar", (d, i) -> {
                getPreferences(0).edit().putBoolean("isolation", selected[0]).putBoolean("fakeua", selected[1]).apply();
                if (selected[0] != isolation) launchServer();
                showLibrary();
            }).setNegativeButton("Cancelar", null).show();
    }

    private void record(String level, String message) {
        String entry = "[" + level + "] " + message;
        synchronized (events) {
            events.addLast(entry);
            while (events.size() > 400) events.removeFirst();
        }
        android.util.Log.i("webOS-simulator", entry);
    }

    private void showLogs() {
        StringBuilder b = new StringBuilder();
        synchronized (events) { for (String event : events) b.append(event).append('\n'); }
        TextView text = text(b.length() == 0 ? "Sem logs" : b.toString(), 12, TEXT, false);
        text.setTextIsSelectable(true); text.setPadding(dp(14), dp(12), dp(14), dp(12));
        ScrollView scroll = new ScrollView(this); scroll.addView(text);
        new AlertDialog.Builder(this).setTitle("Diagnóstico (REAL / MOCK / UNSUPPORTED)")
            .setView(scroll).setPositiveButton("Fechar", null).show();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void startApp(IpkInstaller.InstalledApp app) {
        launchServer(app.id);
        if (localPort == 0) { Toast.makeText(this, "Servidor HTTP indisponível", Toast.LENGTH_LONG).show(); return; }
        currentApp = app;
        main.removeAllViews();
        LinearLayout top = new LinearLayout(this);
        top.setPadding(dp(7), dp(7), dp(7), dp(7));
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setBackgroundColor(PANEL);
        main.addView(top);
        TextView home = secondaryButton("⌂ Biblioteca", this::showLibrary);
        top.addView(home, new LinearLayout.LayoutParams(dp(116), dp(43)));
        TextView label = text("  " + app.title, 15, TEXT, true);
        top.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        top.addView(secondaryButton("Logs", this::showLogs), new LinearLayout.LayoutParams(dp(70), dp(43)));
        webView = new WebView(this);
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(false);
        ws.setAllowContentAccess(false);
        ws.setJavaScriptCanOpenWindowsAutomatically(false);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        ws.setBuiltInZoomControls(false);
        ws.setSupportZoom(false);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);
        if (getPreferences(0).getBoolean("fakeua", true))
            ws.setUserAgentString(profile.optString("userAgent", ws.getUserAgentString()));
        webView.setBackgroundColor(Color.BLACK);
        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0)
            WebView.setWebContentsDebuggingEnabled(true);
        webView.addJavascriptInterface(new LunaBridge(), "AndroidLuna");
        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (!"http".equals(uri.getScheme()) || !"127.0.0.1".equals(uri.getHost()) || uri.getPort() != localPort) {
                    record("UNSUPPORTED", "Navegação externa bloqueada: " + uri);
                    return true;
                }
                return false;
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                record("WEB_ERROR", request.getUrl() + " : " + error.getDescription());
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onConsoleMessage(ConsoleMessage m) {
                record("CONSOLE", m.messageLevel() + " " + m.sourceId() + ":" + m.lineNumber() + " " + m.message());
                return true;
            }
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (webFiles != null) webFiles.onReceiveValue(null);
                webFiles = callback;
                pendingParams = params;
                try {
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("*/*");
                    i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);
                    startActivityForResult(i, PICK_WEB_FILE);
                    return true;
                } catch (Exception e) {
                    webFiles.onReceiveValue(null); webFiles = null;
                    record("ERROR", "SAF chooser: " + e);
                    return false;
                }
            }
        });
        main.addView(webView, new LinearLayout.LayoutParams(-1, 0, 1));
        renderRemote();
        String url = server.urlFor(app, localPort);
        record("INFO", "Executando " + app.id + " em " + url);
        webView.loadUrl(url);
    }

    private void renderRemote() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        row.setPadding(dp(3), dp(5), dp(3), dp(7));
        row.setBackgroundColor(PANEL);
        main.addView(row, new LinearLayout.LayoutParams(-1, dp(65)));
        addKey(row, "◀", "ArrowLeft", 37);
        addKey(row, "▲", "ArrowUp", 38);
        addKey(row, "OK", "Enter", 13);
        addKey(row, "▼", "ArrowDown", 40);
        addKey(row, "▶", "ArrowRight", 39);
        addKey(row, "↩", "Escape", 27);
    }
    private void addKey(LinearLayout row, String label, String key, int code) {
        TextView b = secondaryButton(label, () -> dispatchKey(key, code));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(48), 1);
        lp.leftMargin = dp(3); row.addView(b, lp);
    }
    private void dispatchKey(String key, int code) {
        if (webView == null) return;
        String script = "(function(){['keydown','keyup'].forEach(function(t){document.activeElement.dispatchEvent(new KeyboardEvent(t,{key:"
                + JSONObject.quote(key) + ",keyCode:" + code + ",which:" + code + ",bubbles:true}));});})()";
        webView.evaluateJavascript(script, null);
    }

    private class LunaBridge {
        @JavascriptInterface public String getProfile() { return profile.toString(); }
        @JavascriptInterface public String getAppInfo() {
            IpkInstaller.InstalledApp app = currentApp;
            if (app == null) return "{}";
            try {
                File info = new File(app.directory, "appinfo.json");
                if (info.length() > 128 * 1024) return "{}";
                return new String(Files.readAllBytes(info.toPath()), StandardCharsets.UTF_8);
            } catch (Exception e) { return "{}"; }
        }
        @JavascriptInterface public String request(String uri, String params) {
            if (uri == null || uri.length() > 512 || params == null || params.length() > 128 * 1024)
                return "{\"returnValue\":false,\"errorText\":\"invalid request\",\"__simulator\":\"ERROR\"}";
            JSONObject mocks = profile.optJSONObject("mockResponses");
            JSONObject resp = mocks == null ? null : mocks.optJSONObject(uri);
            if (resp != null) {
                try {
                    JSONObject result = new JSONObject(resp.toString());
                    result.put("__simulator", "MOCK");
                    record("MOCK", "Luna: " + uri);
                    return result.toString();
                } catch (Exception ignored) { }
            }
            record("UNSUPPORTED", "Luna: " + uri + " params=" + params.substring(0, Math.min(params.length(), 160)));
            return "{\"returnValue\":false,\"errorCode\":-1,\"errorText\":\"Unsupported Luna API\",\"__simulator\":\"UNSUPPORTED\"}";
        }
        @JavascriptInterface public void log(String level, String message) {
            record(level == null ? "JS" : level.substring(0, Math.min(24, level.length())),
                   message == null ? "" : message.substring(0, Math.min(800, message.length())));
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == PICK_IPK && result == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            Toast.makeText(this, "Instalando IPK…", Toast.LENGTH_SHORT).show();
            io.execute(() -> {
                try (InputStream stream = getContentResolver().openInputStream(uri)) {
                    if (stream == null) throw new IOException("Arquivo indisponível");
                    IpkInstaller.InstalledApp installed = IpkInstaller.install(stream, appStorage);
                    record("INFO", "IPK instalado: " + installed.id);
                    handler.post(() -> { showLibrary(); Toast.makeText(this, "Instalado: " + installed.title, Toast.LENGTH_LONG).show(); });
                } catch (Exception e) {
                    record("ERROR", "Instalação: " + e.getMessage());
                    handler.post(() -> new AlertDialog.Builder(this).setTitle("IPK não instalado")
                        .setMessage(e.getMessage()).setPositiveButton("OK", null).show());
                }
            });
        } else if (request == PICK_WEB_FILE && webFiles != null) {
            Uri[] resultUris = null;
            if (result == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int n = Math.min(64, data.getClipData().getItemCount());
                    resultUris = new Uri[n];
                    for (int j = 0; j < n; j++) resultUris[j] = data.getClipData().getItemAt(j).getUri();
                } else if (data.getData() != null) resultUris = new Uri[] {data.getData()};
            }
            webFiles.onReceiveValue(resultUris);
            webFiles = null; pendingParams = null;
        }
    }

    @Override public void onBackPressed() {
        if (currentApp != null) showLibrary();
        else super.onBackPressed();
    }

    @Override public void onDestroy() {
        if (webFiles != null) { webFiles.onReceiveValue(null); webFiles = null; }
        if (webView != null) { webView.destroy(); webView = null; }
        if (server != null) server.stop();
        io.shutdownNow();
        super.onDestroy();
    }
}
