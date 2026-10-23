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
    private LunaServiceEngine lunaEngine;
    private volatile int appGeneration = 0;
    private LocalWebServer server;
    private int localPort;
    private WebView webView;
    private WebChromeClient.FileChooserParams pendingParams;
    private ValueCallback<Uri[]> webFiles;
    private LinearLayout main;
    private volatile IpkInstaller.InstalledApp currentApp;
    private float touchX, touchY;
    private boolean pointerMode = false;
    private int pointerX = 960, pointerY = 540;
    private View topBar, remoteBar;
    private boolean immersive = false;

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
        lunaEngine = new LunaServiceEngine(this, profile, appStorage, (id, params) -> {
            final int epoch = appGeneration;
            handler.post(() -> {
                if (epoch == appGeneration && currentApp != null && currentApp.id.equals(id) && webView != null) {
                    webView.evaluateJavascript("if(window.__webOSSimulatorRelaunch)window.__webOSSimulatorRelaunch(" +
                            params.toString() + ")", null);
                }
            });
        });
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
            String mode = appProfileName(appId);
            boolean isolate = CompatibilityProfiles.isolate(mode);
            server = new LocalWebServer(this, appStorage, isolate, true,
                    CompatibilityProfiles.tvViewport(mode));
            localPort = server.start(appId);
            record("INFO", "Servidor HTTP em 127.0.0.1:" + localPort
                    + "; perfil=" + mode + "; COOP/COEP=" + isolate);
            if (localPort < 17822) record("WARN", "Porta alternativa: origem de armazenamento mudou");
        } catch (Exception e) {
            localPort = 0;
            record("ERROR", "Falha ao abrir servidor local: " + e);
        }
    }

    private String appProfileName(String appId) {
        String fallback = getPreferences(0).getString("default_profile", CompatibilityProfiles.BALANCED);
        return CompatibilityProfiles.normalize(appId == null ? fallback :
                getPreferences(0).getString("profile_" + appId, fallback));
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
        appGeneration++;
        applyImmersiveUi(false);
        currentApp = null;
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
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
            TextView compatibility = secondaryButton("⚙ " + CompatibilityProfiles.title(appProfileName(app.id)),
                    () -> showAppCompatibility(app));
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, dp(45));
            cp.topMargin = dp(10);
            card.addView(compatibility, cp);
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
        boolean landscape = getPreferences(0).getBoolean("landscape", true);
        boolean awake = getPreferences(0).getBoolean("awake", true);
        String[] opts = {
            "Perfil padrão: " + CompatibilityProfiles.title(appProfileName(null)),
            "Forçar paisagem: " + (landscape ? "SIM" : "NÃO"),
            "Manter tela ligada: " + (awake ? "SIM" : "NÃO"),
            "Limpar logs de diagnóstico"
        };
        new AlertDialog.Builder(this)
            .setTitle("Configurações · Android WebView")
            .setItems(opts, (d, i) -> {
                if (i == 0) selectDefaultProfile();
                else if (i == 1) { getPreferences(0).edit().putBoolean("landscape", !landscape).apply(); showSettings(); }
                else if (i == 2) { getPreferences(0).edit().putBoolean("awake", !awake).apply(); showSettings(); }
                else if (i == 3) { synchronized (events) { events.clear(); } Toast.makeText(this, "Logs limpos", Toast.LENGTH_SHORT).show(); }
            })
            .setNegativeButton("Fechar", null).show();
    }

    private void selectDefaultProfile() {
        String[] names = {
            CompatibilityProfiles.title(CompatibilityProfiles.BALANCED),
            CompatibilityProfiles.title(CompatibilityProfiles.LEGACY),
            CompatibilityProfiles.title(CompatibilityProfiles.THREADED)
        };
        String[] ids = {CompatibilityProfiles.BALANCED, CompatibilityProfiles.LEGACY,
                CompatibilityProfiles.THREADED};
        new AlertDialog.Builder(this).setTitle("Perfil padrão dos novos IPKs")
            .setSingleChoiceItems(names, indexOfProfile(appProfileName(null)), (d, which) -> {
                getPreferences(0).edit().putString("default_profile", ids[which]).apply();
                launchServer();
                d.dismiss();
                showSettings();
            }).setNegativeButton("Cancelar", null).show();
    }

    private static int indexOfProfile(String id) {
        if (CompatibilityProfiles.LEGACY.equals(id)) return 1;
        if (CompatibilityProfiles.THREADED.equals(id)) return 2;
        return 0;
    }

    private void showAppCompatibility(IpkInstaller.InstalledApp app) {
        String[] names = {
            CompatibilityProfiles.title(CompatibilityProfiles.BALANCED)
                + "\nSem COOP/COEP; Chromium real; melhor para conteúdo externo.",
            CompatibilityProfiles.title(CompatibilityProfiles.LEGACY)
                + "\nUser-Agent LG, viewport 1920×1080; sem isolamento.",
            CompatibilityProfiles.title(CompatibilityProfiles.THREADED)
                + "\nCOOP/COEP e viewport 1080p; necessário em alguns WASM multithread."
        };
        String[] ids = {CompatibilityProfiles.BALANCED,
                CompatibilityProfiles.LEGACY, CompatibilityProfiles.THREADED};
        new AlertDialog.Builder(this)
            .setTitle("Perfil do IPK · " + app.title)
            .setSingleChoiceItems(names, indexOfProfile(appProfileName(app.id)), (dialog, which) -> {
                getPreferences(0).edit().putString("profile_" + app.id, ids[which]).apply();
                record("PROFILE", app.id + " -> " + ids[which]);
                dialog.dismiss();
                if (currentApp != null && currentApp.id.equals(app.id)) startApp(app);
                else showLibrary();
            })
            .setNegativeButton("Cancelar", null).show();
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
        appGeneration++;
        applyImmersiveUi(false);
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
        launchServer(app.id);
        if (localPort == 0) { Toast.makeText(this, "Servidor HTTP indisponível", Toast.LENGTH_LONG).show(); return; }
        currentApp = app;
        if (getPreferences(0).getBoolean("landscape", true))
            setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        else setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        if (getPreferences(0).getBoolean("awake", true))
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        main.removeAllViews();
        LinearLayout top = new LinearLayout(this);
        top.setPadding(dp(7), dp(7), dp(7), dp(7));
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setBackgroundColor(PANEL);
        topBar = top;
        main.addView(top);
        TextView home = secondaryButton("⌂ Biblioteca", this::showLibrary);
        top.addView(home, new LinearLayout.LayoutParams(dp(116), dp(43)));
        TextView label = text("  " + app.title, 15, TEXT, true);
        top.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        top.addView(secondaryButton("GPU", this::showGraphicsDoctor), new LinearLayout.LayoutParams(dp(62), dp(43)));
        top.addView(secondaryButton("Logs", this::showLogs), new LinearLayout.LayoutParams(dp(62), dp(43)));
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
        ws.setUseWideViewPort(true);
        ws.setLoadWithOverviewMode(true);
        ws.setOffscreenPreRaster(false);
        if (CompatibilityProfiles.fakeUA(appProfileName(app.id)))
            ws.setUserAgentString(profile.optString("userAgent", ws.getUserAgentString()));
        webView.setBackgroundColor(Color.BLACK);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0)
            WebView.setWebContentsDebuggingEnabled(true);
        webView.addJavascriptInterface(new LunaBridge(app.id, appGeneration), "AndroidLuna");
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
            @Override public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
                if (response.getStatusCode() >= 400)
                    record("HTTP_" + response.getStatusCode(), request.getUrl().toString());
            }
            @Override public void onPageFinished(WebView view, String url) {
                record("PAGE", "Carregamento principal concluído: " + url);
            }
            @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                record("GPU_ERROR", "Processo WebView encerrado: " +
                        (detail.didCrash() ? "crash" : "terminação") + ", prioridade=" + detail.rendererPriorityAtExit());
                if (view.getParent() instanceof android.view.ViewGroup)
                    ((android.view.ViewGroup) view.getParent()).removeView(view);
                view.destroy();
                if (webView == view) webView = null;
                Toast.makeText(MainActivity.this, "Motor gráfico encerrado. Verifique os logs.", Toast.LENGTH_LONG).show();
                showLibrary();
                return true;
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(() -> {
                    // Never auto-grant microphone/camera from an imported third-party IPK.
                    record("PERMISSION", "Recursos solicitados: " + java.util.Arrays.toString(request.getResources()));
                    request.deny();
                });
            }
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
        applyScale();
        String url = server.urlFor(app, localPort);
        record("INFO", "Executando " + app.id + " em " + url);
        webView.loadUrl(url);
    }

    private void applyImmersiveUi(boolean enabled) {
        immersive = enabled && webView != null;
        if (topBar != null) topBar.setVisibility(immersive ? View.GONE : View.VISIBLE);
        if (remoteBar != null) remoteBar.setVisibility(immersive ? View.GONE : View.VISIBLE);
        int flags = immersive ? (View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY) : 0;
        getWindow().getDecorView().setSystemUiVisibility(flags);
    }

    private void renderRemote() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        row.setPadding(dp(3), dp(5), dp(3), dp(7));
        row.setBackgroundColor(PANEL);
        remoteBar = row;
        main.addView(row, new LinearLayout.LayoutParams(-1, dp(65)));
        addKey(row, "◀", "ArrowLeft", 37);
        addKey(row, "▲", "ArrowUp", 38);
        addKey(row, "OK", "Enter", 13);
        addKey(row, "▼", "ArrowDown", 40);
        addKey(row, "▶", "ArrowRight", 39);
        addKey(row, "↩", "Escape", 27);
        addKey(row, "⌂", "Home", 36);
        TextView tools = secondaryButton("⋮", this::showInputTools);
        LinearLayout.LayoutParams toolParams = new LinearLayout.LayoutParams(0, dp(48), 1);
        toolParams.leftMargin = dp(3); row.addView(tools, toolParams);
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


    /** Device-level visual scaling; this does not alter the IPK's rendering backend. */
    private void applyScale() {
        if (webView == null || currentApp == null) return;
        int zoom = getPreferences(0).getInt("zoom_" + currentApp.id, 100);
        // Scale 0 lets Chromium fit the logical 1080p viewport to the actual Android screen.
        webView.setInitialScale(zoom == 100 ? 0 : zoom);
        webView.getSettings().setUseWideViewPort(true);
        webView.getSettings().setLoadWithOverviewMode(true);
    }

    private void showInputTools() {
        if (webView == null) return;
        String[] options = {"Controle remoto LG completo", "Mouse/touchpad virtual", "Zoom e escala", "Diagnóstico gráfico", "Teste GPU independente", "Perfil de compatibilidade", "Teste de APIs Luna webOS 22", "Tela cheia (Back para restaurar)", "Exportar logs"};
        new AlertDialog.Builder(this).setTitle("Ferramentas da TV")
            .setItems(options, (d, which) -> {
                switch (which) {
                    case 0: showFullRemote(); break;
                    case 1: showTouchpad(); break;
                    case 2: showZoom(); break;
                    case 3: showGraphicsDoctor(); break;
                    case 4: showGraphicsSelfTest(); break;
                    case 5: if (currentApp != null) showAppCompatibility(currentApp); break;
                    case 6: showLunaSelfTest(); break;
                    case 7: applyImmersiveUi(true); break;
                    case 8: exportLogs(); break;
                }
            }).show();
    }

    private void showFullRemote() {
        String[] labels = {"HOME", "BACK", "OK", "PLAY/PAUSE", "VOL +", "VOL -", "CH +", "CH -",
                "RED", "GREEN", "YELLOW", "BLUE", "0", "1", "2", "3", "4", "5", "6", "7", "8", "9"};
        String[] keys = {"Home", "Escape", "Enter", "MediaPlayPause", "AudioVolumeUp", "AudioVolumeDown",
                "PageUp", "PageDown", "F1", "F2", "F3", "F4", "0", "1", "2", "3", "4", "5", "6", "7", "8", "9"};
        new AlertDialog.Builder(this).setTitle("LG Magic Remote · teclas compatíveis")
            .setItems(labels, (d, which) -> dispatchKey(keys[which], 0))
            .setNegativeButton("Fechar", null).show();
    }

    private void showZoom() {
        if (currentApp == null || webView == null) return;
        String[] levels = {"50%", "75%", "100% (padrão)", "125%", "150%", "200%", "300%"};
        int[] values = {50, 75, 100, 125, 150, 200, 300};
        new AlertDialog.Builder(this).setTitle("Escala visual · viewport TV")
            .setSingleChoiceItems(levels, -1, (dialog, index) -> {
                int value = values[index];
                getPreferences(0).edit().putInt("zoom_" + currentApp.id, value).apply();
                webView.setInitialScale(value);
                webView.evaluateJavascript("document.documentElement.style.zoom=" + (value / 100.0) + ";", null);
                record("INFO", "Zoom visual/CSS " + value + "%; viewport lógico pode variar");
                dialog.dismiss();
            }).setNegativeButton("Cancelar", null).show();
    }

    private void showTouchpad() {
        if (webView == null) return;
        pointerMode = true;
        TextView pad = text("TOUCHPAD LG\n\nArraste para mover o ponteiro\nToque para clicar", 16, TEXT, true);
        pad.setGravity(Gravity.CENTER);
        pad.setBackground(background(PANEL, 15));
        pad.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    touchX = event.getX(); touchY = event.getY(); return true;
                case MotionEvent.ACTION_MOVE:
                    pointerX = Math.max(0, Math.min(webView.getWidth(), pointerX + Math.round((event.getX() - touchX) * 2)));
                    pointerY = Math.max(0, Math.min(webView.getHeight(), pointerY + Math.round((event.getY() - touchY) * 2)));
                    touchX = event.getX(); touchY = event.getY();
                    sendPointer(false); return true;
                case MotionEvent.ACTION_UP:
                    sendPointer(true); return true;
            }
            return true;
        });
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Magic Remote · mouse virtual")
            .setView(pad).setPositiveButton("Fechar", null).create();
        dialog.setOnDismissListener(d -> pointerMode = false);
        dialog.show();
    }

    private void sendPointer(boolean click) {
        if (webView == null) return;
        int x = Math.max(0, pointerX), y = Math.max(0, pointerY);
        String js = "(function(){var x=Math.round(" + x
                + "*innerWidth/" + Math.max(1, webView.getWidth()) + "),y=Math.round(" + y
                + "*innerHeight/" + Math.max(1, webView.getHeight())
                + "),el=document.elementFromPoint(x,y);if(!el)return;"
                + "el.dispatchEvent(new MouseEvent('mousemove',{bubbles:true,clientX:x,clientY:y}));"
                + (click ? "el.dispatchEvent(new MouseEvent('mousedown',{bubbles:true,clientX:x,clientY:y}));"
                    + "el.dispatchEvent(new MouseEvent('mouseup',{bubbles:true,clientX:x,clientY:y}));"
                    + "el.click();" : "")
                + "})()";
        webView.evaluateJavascript(js, null);
    }

    private void showGraphicsDoctor() {
        if (webView == null) { Toast.makeText(this, "Abra um IPK primeiro", Toast.LENGTH_SHORT).show(); return; }
        webView.evaluateJavascript("(function(){return JSON.stringify(window.WebOSGraphicsDoctor ? window.WebOSGraphicsDoctor.scan() : {error:'Graphics Doctor ausente'});})()", raw -> {
            String display = raw == null ? "Sem resposta" : raw;
            try { display = new org.json.JSONTokener(raw).nextValue().toString(); } catch (Exception ignored) {}
            record("GPU", display);
            new AlertDialog.Builder(this).setTitle("Graphics Doctor · diagnóstico real")
                .setMessage(display).setPositiveButton("Fechar", null)
                .setNeutralButton("Logs", (d, which) -> showLogs()).show();
        });
    }

    /** Independent visual verification; does not navigate away from a running IPK. */
    @SuppressLint("SetJavaScriptEnabled")
    private void showGraphicsSelfTest() {
        if (localPort == 0) {
            Toast.makeText(this, "Servidor local indisponível", Toast.LENGTH_LONG).show();
            return;
        }
        WebView diagnostic = new WebView(this);
        diagnostic.getSettings().setJavaScriptEnabled(true);
        diagnostic.setBackgroundColor(0xff10131c);
        diagnostic.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onConsoleMessage(ConsoleMessage message) {
                record("GPU_TEST", message.message());
                return true;
            }
        });
        diagnostic.setWebViewClient(new WebViewClient() {
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                record("GPU_TEST_ERROR", request.getUrl() + ": " + error.getDescription());
            }
        });
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Graphics Doctor · Teste independente")
                .setView(diagnostic)
                .setPositiveButton("Fechar", null)
                .create();
        dialog.setOnDismissListener(d -> {
            diagnostic.stopLoading();
            diagnostic.destroy();
        });
        dialog.show();
        diagnostic.getLayoutParams().height = dp(420);
        diagnostic.requestLayout();
        diagnostic.loadUrl("http://127.0.0.1:" + localPort + "/__graphics_selftest.html");
    }

    /** Read-only Luna smoke test; runs in the opened IPK without touching its game state. */
    private void showLunaSelfTest() {
        if (webView == null || currentApp == null) {
            Toast.makeText(this, "Abra um IPK primeiro", Toast.LENGTH_SHORT).show();
            return;
        }
        String test = "(function(){var queries=["
                + "['luna://com.palm.connectionmanager/getStatus',{}],"
                + "['luna://com.palm.systemservice/time/getSystemTime',{}],"
                + "['luna://com.webos.settingsservice/getSystemSettings',{keys:['localeInfo']}],"
                + "['luna://com.webos.service.tv.systemproperty/getSystemInfo',{keys:['modelName','sdkVersion']}],"
                + "['luna://com.webos.applicationManager/getAppLoadStatus',{appId:window.webOS&&webOS.fetchAppId?webOS.fetchAppId():''}],"
                + "['luna://com.webos.service.drm/isLoaded',{}]];"
                + "var result={api:{webOS:typeof webOS,webOSDev:typeof webOSDev,"
                + "PalmServiceBridge:typeof PalmServiceBridge,"
                + "fetchAppId:window.webOS?typeof webOS.fetchAppId:'missing',"
                + "deviceInfo:window.webOS?typeof webOS.deviceInfo:'missing'},results:[]};"
                + "queries.forEach(function(pair){try{var response=JSON.parse(AndroidLuna.request(pair[0],JSON.stringify(pair[1])));"
                + "result.results.push({uri:pair[0],status:response.__simulator,ok:response.returnValue,"
                + "error:response.errorText||null});}catch(e){result.results.push({uri:pair[0],error:String(e)});}});"
                + "return JSON.stringify(result);})()";
        webView.evaluateJavascript(test, raw -> {
            String output = raw == null ? "Sem resposta" : raw;
            try { output = new org.json.JSONTokener(raw).nextValue().toString(); }
            catch (Exception ignored) {}
            record("LUNA_TEST", output);
            new AlertDialog.Builder(this).setTitle("Luna webOS TV 22 · simulada")
                .setMessage(output).setPositiveButton("Fechar", null)
                .setNeutralButton("Logs", (d, which) -> showLogs()).show();
        });
    }

    private void exportLogs() {
        StringBuilder content = new StringBuilder("webOS Simulator / LG UQ8050PSB\n");
        if (currentApp != null) content.append("IPK: ").append(currentApp.id)
                .append("; perfil: ").append(appProfileName(currentApp.id)).append('\n');
        synchronized (events) { for (String event : events) content.append(event).append('\n'); }
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT, content.toString());
        startActivity(Intent.createChooser(send, "Compartilhar diagnóstico"));
    }

    private class LunaBridge {
        private final String appId;
        private final int epoch;
        LunaBridge(String appId, int epoch) { this.appId = appId; this.epoch = epoch; }

        private boolean active() {
            IpkInstaller.InstalledApp app = currentApp;
            return app != null && epoch == appGeneration && app.id.equals(appId);
        }

        @JavascriptInterface public String getProfile() {
            return active() ? profile.toString() : "{}";
        }

        @JavascriptInterface public String getAppInfo() {
            if (!active()) return "{}";
            try {
                File file = new File(appStorage, appId + "/appinfo.json");
                if (!file.isFile() || file.length() > 128 * 1024) return "{}";
                return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            } catch (Exception e) { return "{}"; }
        }

        @JavascriptInterface public String request(String uri, String params) {
            if (!active()) return LunaServiceEngine.error(-1, "IPK session expired").toString();
            if (uri == null || uri.length() > 512 || params == null || params.length() > 131072)
                return LunaServiceEngine.error(-1, "Invalid Luna request").toString();
            JSONObject data;
            try { data = new JSONObject(params); }
            catch (Exception e) { return LunaServiceEngine.error(-1, "Invalid Luna JSON").toString(); }
            JSONObject response = lunaEngine.request(appId, uri, data);
            String simulator = response.optString("__simulator", "UNKNOWN");
            String level = response.optBoolean("returnValue", false) ? "LUNA_" + simulator : "LUNA_UNSUPPORTED";
            record(level, uri + " " + (response.optBoolean("returnValue") ? "OK" :
                    response.optString("errorText", "error")));
            return response.toString();
        }

        @JavascriptInterface public void log(String level, String message) {
            if (!active()) return;
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

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (webView != null && currentApp != null
                && (event.getSource() & (InputDevice.SOURCE_GAMEPAD | InputDevice.SOURCE_DPAD)) != 0) {
            String key = null;
            int code = 0;
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP: key = "ArrowUp"; code = 38; break;
                case KeyEvent.KEYCODE_DPAD_DOWN: key = "ArrowDown"; code = 40; break;
                case KeyEvent.KEYCODE_DPAD_LEFT: key = "ArrowLeft"; code = 37; break;
                case KeyEvent.KEYCODE_DPAD_RIGHT: key = "ArrowRight"; code = 39; break;
                case KeyEvent.KEYCODE_BUTTON_A: key = "Enter"; code = 13; break;
                case KeyEvent.KEYCODE_BUTTON_B: key = "Escape"; code = 27; break;
                case KeyEvent.KEYCODE_BUTTON_START: key = "Enter"; code = 13; break;
                case KeyEvent.KEYCODE_BUTTON_SELECT: key = "Escape"; code = 27; break;
                default: break;
            }
            if (key != null) {
                String type = event.getAction() == KeyEvent.ACTION_DOWN ? "keydown" : "keyup";
                String script = "(function(){var e=new KeyboardEvent(" + JSONObject.quote(type)
                        + ",{key:" + JSONObject.quote(key) + ",keyCode:" + code
                        + ",which:" + code + ",bubbles:true,cancelable:true});"
                        + "(document.activeElement||document).dispatchEvent(e);})()";
                webView.evaluateJavascript(script, null);
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override public void onBackPressed() {
        if (immersive) applyImmersiveUi(false);
        else if (currentApp != null) showLibrary();
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
