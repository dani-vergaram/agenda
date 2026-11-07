package co.daniela.agenda.movil;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Inicio de sesión y accesos rápidos. La agenda completa se abre en el navegador. */
public class MainActivity extends Activity {
    private static final int FONDO = Color.rgb(251, 246, 243);
    private static final int TINTA = Color.rgb(30, 42, 47);
    private static final int VERDE = Color.rgb(62, 159, 147);
    private static final int GRIS = Color.rgb(95, 107, 111);

    private final ExecutorService fondo = Executors.newSingleThreadExecutor();
    private Api api;
    private LinearLayout raiz;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(FONDO);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        api = new Api(getSharedPreferences("sesion", Context.MODE_PRIVATE));
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(FONDO);
        raiz = new LinearLayout(this);
        raiz.setOrientation(LinearLayout.VERTICAL);
        raiz.setPadding(dp(24), dp(48), dp(24), dp(32));
        scroll.addView(raiz);
        setContentView(scroll);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { "android.permission.POST_NOTIFICATIONS" }, 1);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (api.conSesion()) { Avisos.programar(this); pantallaLista(); } else pantallaEntrar();
    }

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
        if (principal) { g.setColor(VERDE); x.setTextColor(TINTA); }
        else { g.setColor(Color.WHITE); g.setStroke(dp(1), Color.rgb(236, 220, 213)); x.setTextColor(TINTA); }
        x.setBackground(g);
        return x;
    }

    private LinearLayout.LayoutParams fila(int arriba) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(arriba);
        return p;
    }

    private EditText campo(String pista, int tipo) {
        EditText e = new EditText(this);
        e.setHint(pista); e.setInputType(tipo); e.setTextColor(TINTA); e.setSingleLine(true);
        return e;
    }

    private void pantallaEntrar() {
        raiz.removeAllViews();
        raiz.addView(texto("Agenda", 30, TINTA, true));
        raiz.addView(texto("Entra con el mismo correo y contraseña de tu agenda.", 15, GRIS, false), fila(6));
        EditText correo = campo("Correo", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        EditText clave = campo("Contraseña", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        raiz.addView(correo, fila(20));
        raiz.addView(clave, fila(8));
        raiz.addView(boton("Entrar", true, v -> {
            String c = correo.getText().toString().trim(), k = clave.getText().toString();
            fondo.execute(() -> {
                try {
                    api.entrar(c, k);
                    Avisos.programar(this);
                    Widget.refrescar(getApplicationContext(), null);
                    runOnUiThread(this::pantallaLista);
                } catch (Exception e) {
                    runOnUiThread(() -> Toast.makeText(this, "No pude entrar. Revisa el correo, la contraseña y tu conexión.", Toast.LENGTH_LONG).show());
                }
            });
        }), fila(16));
    }

    private void pantallaLista() {
        raiz.removeAllViews();
        raiz.addView(texto("Agenda", 30, TINTA, true));
        raiz.addView(texto("Sesión iniciada. Tus avisos y el widget se actualizan solos cada 15 minutos más o menos.", 15, GRIS, false), fila(6));
        raiz.addView(boton("Abrir mi agenda", true, v -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.AGENDA_URL)))), fila(24));
        raiz.addView(boton("Actualizar widget ahora", false, v -> {
            Widget.refrescar(getApplicationContext(), null);
            Toast.makeText(this, "Actualizando el widget…", Toast.LENGTH_SHORT).show();
        }), fila(10));
        raiz.addView(texto("Cómo poner el widget", 17, TINTA, true), fila(28));
        raiz.addView(texto("1. Mantén presionado un espacio vacío de la pantalla de inicio.\n2. Toca Widgets.\n3. Busca Agenda y arrastra \"Agenda de hoy\" a la pantalla.\n4. Si quieres, mantenlo presionado para cambiar su tamaño.", 15, GRIS, false), fila(6));
        raiz.addView(boton("Cerrar sesión", false, v -> {
            Avisos.cancelar(this); api.salir();
            Widget.refrescar(getApplicationContext(), null);
            pantallaEntrar();
        }), fila(28));
    }
}
