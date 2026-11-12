package co.daniela.agenda.movil;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import org.json.JSONObject;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** La agenda completa dentro de la app, más los ajustes del widget. */
public class MainActivity extends Activity {
    private static final int FONDO = Color.rgb(251, 246, 243);
    private static final int TINTA = Color.rgb(30, 42, 47);
    private static final int VERDE = Color.rgb(62, 159, 147);
    private static final int GRIS = Color.rgb(95, 107, 111);

    private final ExecutorService fondo = Executors.newSingleThreadExecutor();
    private Api api;
    private WebView web;
    private LinearLayout pantallaAgenda;
    private String correoPendiente, clavePendiente;
    private boolean agregarAlCargar, enAjustes;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(FONDO);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        api = new Api(getSharedPreferences("sesion", Context.MODE_PRIVATE));
        agregarAlCargar = getIntent().getBooleanExtra("agregar", false);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { "android.permission.POST_NOTIFICATIONS" }, 1);
        }
        if (api.conSesion()) { Avisos.programar(this); mostrarAgenda(); } else pantallaEntrar();
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        if (i.getBooleanExtra("agregar", false)) {
            if (web != null && pantallaAgenda != null) { enAjustes = false; setContentView(pantallaAgenda); enfocarAgregar(); }
            else agregarAlCargar = true;
        }
    }

    @Override public void onBackPressed() {
        if (enAjustes && pantallaAgenda != null) { enAjustes = false; setContentView(pantallaAgenda); }
        else if (!enAjustes && web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override protected void onPause() { super.onPause(); Widget.refrescar(getApplicationContext(), null); }

    @Override protected void onDestroy() { fondo.shutdown(); super.onDestroy(); }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private TextView texto(String s, float tam, int color, boolean negrita) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(tam); t.setTextColor(color);
        if (negrita) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        return t;
    }

    private Button boton(String s, boolean principal, View.OnClickListener l) {
        Button x = new Button(this);
        x.setText(s); x.setAllCaps(false); x.setTextSize(16); x.setOnClickListener(l);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(10));
        if (principal) g.setColor(VERDE); else { g.setColor(Color.WHITE); g.setStroke(dp(1), Color.rgb(236, 220, 213)); }
        x.setTextColor(TINTA);
        x.setBackground(g);
        return x;
    }

    private LinearLayout.LayoutParams fila(int arriba) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(arriba);
        return p;
    }

    private LinearLayout columna() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(24), dp(40), dp(24), dp(32));
        return l;
    }

    private void mostrarScroll(LinearLayout contenido) {
        ScrollView s = new ScrollView(this);
        s.setBackgroundColor(FONDO);
        s.addView(contenido);
        setContentView(s);
    }

    private EditText campo(String pista, int tipo) {
        EditText e = new EditText(this);
        e.setHint(pista); e.setTextColor(TINTA); e.setSingleLine(true); e.setInputType(tipo);
        if ((tipo & InputType.TYPE_TEXT_VARIATION_PASSWORD) != 0) e.setTransformationMethod(android.text.method.PasswordTransformationMethod.getInstance());
        return e;
    }

    // ---------- Inicio de sesión ----------
    private void pantallaEntrar() {
        LinearLayout raiz = columna();
        raiz.addView(texto("Agenda", 30, TINTA, true));
        raiz.addView(texto("Entra con el mismo correo y contraseña de tu agenda.", 15, GRIS, false), fila(6));
        EditText correo = campo("Correo", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        EditText clave = campo("Contraseña", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        raiz.addView(correo, fila(20));
        raiz.addView(clave, fila(8));
        TextView error = texto("", 14, Color.rgb(180, 71, 47), false);
        error.setVisibility(View.GONE);
        raiz.addView(boton("Entrar", true, v -> {
            error.setVisibility(View.GONE);
            String c = correo.getText().toString().trim(), k = clave.getText().toString();
            fondo.execute(() -> {
                try {
                    api.entrar(c, k);
                    Avisos.programar(this);
                    Widget.refrescar(getApplicationContext(), null);
                    runOnUiThread(() -> { correoPendiente = c; clavePendiente = k; mostrarAgenda(); });
                } catch (Exception e) {
                    String msj = motivo(e);
                    runOnUiThread(() -> { error.setText(msj); error.setVisibility(View.VISIBLE); });
                }
            });
        }), fila(16));
        raiz.addView(error, fila(12));
        mostrarScroll(raiz);
    }

    static String motivo(Exception e) {
        String d = String.valueOf(e.getMessage());
        if (d.contains("INVALID_LOGIN_CREDENTIALS") || d.contains("INVALID_PASSWORD") || d.contains("EMAIL_NOT_FOUND"))
            return "El correo o la contraseña no coinciden con tu usuario. Usa los mismos con los que entras a la agenda.";
        if (d.contains("INVALID_EMAIL")) return "El correo no tiene un formato válido. Revisa que no tenga espacios.";
        if (d.contains("USER_DISABLED")) return "Este usuario está desactivado.";
        if (d.contains("TOO_MANY_ATTEMPTS")) return "Hubo demasiados intentos. Espera unos minutos y vuelve a intentarlo.";
        if (e instanceof java.net.UnknownHostException || e instanceof java.net.SocketTimeoutException || e instanceof java.net.ConnectException)
            return "No hay conexión a internet. Revisa el wifi o los datos y vuelve a intentarlo.";
        return "No pude entrar. Detalle: " + d.substring(0, Math.min(d.length(), 200));
    }

    // ---------- Agenda dentro de la app ----------
    private void mostrarAgenda() {
        if (pantallaAgenda == null) {
            pantallaAgenda = new LinearLayout(this);
            pantallaAgenda.setOrientation(LinearLayout.VERTICAL);
            pantallaAgenda.setBackgroundColor(FONDO);
            LinearLayout barra = new LinearLayout(this);
            barra.setGravity(Gravity.CENTER_VERTICAL);
            barra.setPadding(dp(16), dp(8), dp(8), dp(4));
            TextView t = texto("Agenda", 20, TINTA, true);
            barra.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            TextView ajustes = texto("Widget y ajustes", 14, VERDE, true);
            ajustes.setPadding(dp(12), dp(10), dp(12), dp(10));
            ajustes.setOnClickListener(v -> pantallaAjustes());
            barra.addView(ajustes);
            pantallaAgenda.addView(barra);
            web = new WebView(this);
            web.setId(View.generateViewId());
            WebSettings w = web.getSettings();
            w.setJavaScriptEnabled(true);
            w.setDomStorageEnabled(true);
            w.setDatabaseEnabled(true);
            w.setMediaPlaybackRequiresUserGesture(true);
            CookieManager.getInstance().setAcceptCookie(true);
            web.setWebViewClient(new WebViewClient() {
                @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                    Uri u = r.getUrl();
                    if (Uri.parse(BuildConfig.AGENDA_URL).getHost().equals(u.getHost())) return false;
                    try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignorado) { }
                    return true;
                }
                @Override public void onPageFinished(WebView v, String url) {
                    if (correoPendiente != null) {
                        String js = "(function(){var n=0,c=" + JSONObject.quote(correoPendiente) + ",k=" + JSONObject.quote(clavePendiente) + ";"
                            + "var i=setInterval(function(){n++;var l=document.getElementById('login');"
                            + "if(l&&!l.hidden){document.getElementById('correo').value=c;document.getElementById('clave').value=k;l.requestSubmit();clearInterval(i);}"
                            + "var a=document.getElementById('app');if((a&&!a.hidden)||n>40)clearInterval(i);},250);})();";
                        v.evaluateJavascript(js, null);
                        correoPendiente = null; clavePendiente = null;
                    }
                    if (agregarAlCargar) { agregarAlCargar = false; enfocarAgregar(); }
                }
            });
            pantallaAgenda.addView(web, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
            web.loadUrl(BuildConfig.AGENDA_URL);
        }
        enAjustes = false;
        setContentView(pantallaAgenda);
    }

    private void enfocarAgregar() {
        if (web == null) return;
        web.evaluateJavascript("(function(){var n=0;var i=setInterval(function(){n++;var a=document.getElementById('app'),t=document.getElementById('tabDia'),x=document.getElementById('nuevoTexto');"
            + "if(a&&!a.hidden&&x){if(t)t.click();x.scrollIntoView({block:'center'});x.focus();clearInterval(i);}if(n>40)clearInterval(i);},250);})();", null);
        web.requestFocus();
        android.view.inputmethod.InputMethodManager im = (android.view.inputmethod.InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        web.postDelayed(() -> { if (im != null) im.showSoftInput(web, 0); }, 900);
    }

    // ---------- Ajustes del widget ----------
    private void pantallaAjustes() {
        SharedPreferences p = getSharedPreferences(Widget.PREFS, Context.MODE_PRIVATE);
        LinearLayout raiz = columna();
        TextView volver = texto("‹ Volver a la agenda", 16, VERDE, true);
        volver.setPadding(0, dp(4), 0, dp(4));
        volver.setOnClickListener(v -> { enAjustes = false; setContentView(pantallaAgenda); });
        raiz.addView(volver);
        raiz.addView(texto("Widget", 26, TINTA, true), fila(16));

        raiz.addView(texto("Qué días mostrar", 16, TINTA, true), fila(20));
        RadioGroup dias = new RadioGroup(this);
        int[][] opciones = { { 1, 0 }, { 3, 0 }, { 7, 0 } };
        String[] nombres = { "Solo hoy", "Hoy y los dos días siguientes", "Los próximos 7 días" };
        int actual = p.getInt("dias", 3);
        for (int i = 0; i < 3; i++) {
            RadioButton r = new RadioButton(this);
            r.setText(nombres[i]); r.setTextColor(TINTA); r.setTextSize(15); r.setId(View.generateViewId());
            dias.addView(r);
            if (opciones[i][0] == actual) r.setChecked(true);
            final int n = opciones[i][0];
            r.setOnCheckedChangeListener((b, sel) -> { if (sel) { p.edit().putInt("dias", n).apply(); Widget.refrescar(getApplicationContext(), null); } });
        }
        raiz.addView(dias, fila(4));

        android.widget.CheckBox prio = new android.widget.CheckBox(this);
        prio.setText("Mostrar arriba todos los urgentes e importantes");
        prio.setTextColor(TINTA); prio.setTextSize(15);
        prio.setChecked(p.getBoolean("prioridades", true));
        prio.setOnCheckedChangeListener((b, sel) -> { p.edit().putBoolean("prioridades", sel).apply(); Widget.refrescar(getApplicationContext(), null); });
        raiz.addView(prio, fila(16));
        raiz.addView(texto("Fondo", 16, TINTA, true), fila(20));
        RadioGroup tono = new RadioGroup(this);
        tono.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton claro = new RadioButton(this), oscuro = new RadioButton(this);
        claro.setText("Claro"); oscuro.setText("Oscuro");
        claro.setId(View.generateViewId()); oscuro.setId(View.generateViewId());
        for (RadioButton r : new RadioButton[] { claro, oscuro }) { r.setTextColor(TINTA); r.setTextSize(15); r.setPadding(0, 0, dp(20), 0); tono.addView(r); }
        (p.getBoolean("oscuro", false) ? oscuro : claro).setChecked(true);
        oscuro.setOnCheckedChangeListener((b, sel) -> { p.edit().putBoolean("oscuro", sel).apply(); Widget.refrescar(getApplicationContext(), null); });
        raiz.addView(tono, fila(4));

        TextView etiqueta = texto("", 16, TINTA, true);
        raiz.addView(etiqueta, fila(20));
        SeekBar transp = new SeekBar(this);
        transp.setMax(90);
        int op = p.getInt("opacidad", 100);
        transp.setProgress(100 - op);
        etiqueta.setText("Transparencia: " + (100 - op) + " %");
        transp.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int v, boolean u) { etiqueta.setText("Transparencia: " + v + " %"); }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { p.edit().putInt("opacidad", 100 - s.getProgress()).apply(); Widget.refrescar(getApplicationContext(), null); }
        });
        raiz.addView(transp, fila(4));
        raiz.addView(texto("Los cambios se ven en el widget en unos segundos.", 13, GRIS, false), fila(4));

        raiz.addView(texto("Cómo poner el widget", 16, TINTA, true), fila(24));
        raiz.addView(texto("Mantén presionado un espacio vacío de la pantalla de inicio, toca Widgets, busca Agenda y arrastra \"Agenda de hoy\". Mantenlo presionado para cambiar su tamaño.", 14, GRIS, false), fila(4));

        raiz.addView(boton("Cerrar sesión", false, v -> {
            Avisos.cancelar(this); api.salir();
            CookieManager.getInstance().removeAllCookies(null);
            WebStorage.getInstance().deleteAllData();
            if (web != null) { web.destroy(); web = null; }
            pantallaAgenda = null;
            Widget.refrescar(getApplicationContext(), null);
            pantallaEntrar();
        }), fila(32));
        enAjustes = true;
        mostrarScroll(raiz);
    }
}
