package co.daniela.agenda;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Paint;
import android.content.pm.PackageManager;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Hoy y atrasados: chulear, tachar y agregar desde el reloj. */
public class MainActivity extends Activity {
    private static final int VERDE = Color.rgb(155, 228, 209);
    private static final int AZUL = Color.rgb(252, 225, 217);
    private static final int ROJO = Color.rgb(255, 177, 153);

    private final ExecutorService fondo = Executors.newSingleThreadExecutor();
    private Api api;
    private ScrollView scroll;
    private LinearLayout raiz;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        api = new Api(getSharedPreferences("sesion", Context.MODE_PRIVATE));
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        raiz = new LinearLayout(this);
        raiz.setOrientation(LinearLayout.VERTICAL);
        raiz.setGravity(Gravity.CENTER_HORIZONTAL);
        raiz.setPadding(dp(22), dp(36), dp(22), dp(48)); // márgenes para la pantalla redonda
        scroll.addView(raiz);
        setContentView(scroll);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { "android.permission.POST_NOTIFICATIONS" }, 1);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (api.conSesion()) { Avisos.programar(this); cargar(); } else pantallaEntrar();
        scroll.requestFocus(); // permite desplazarse con el bisel
    }

    @Override protected void onDestroy() { fondo.shutdown(); super.onDestroy(); }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private TextView etiqueta(String s, float tam, int color) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(tam); t.setTextColor(color); t.setGravity(Gravity.CENTER);
        return t;
    }

    private Button boton(String s, View.OnClickListener l) {
        Button x = new Button(this);
        x.setText(s); x.setAllCaps(false); x.setOnClickListener(l);
        return x;
    }

    private LinearLayout.LayoutParams ancho() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(4);
        return p;
    }

    private void aviso(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private void pantallaEntrar() {
        raiz.removeAllViews();
        raiz.addView(etiqueta("Agenda", 18, Color.WHITE));
        EditText correo = new EditText(this);
        correo.setHint("Correo");
        correo.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        EditText clave = new EditText(this);
        clave.setHint("Contraseña");
        clave.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        raiz.addView(correo, ancho());
        raiz.addView(clave, ancho());
        raiz.addView(boton("Entrar", v -> {
            String c = correo.getText().toString().trim(), k = clave.getText().toString();
            fondo.execute(() -> {
                try {
                    api.entrar(c, k);
                    Avisos.programar(this);
                    runOnUiThread(this::cargar);
                } catch (Exception e) {
                    runOnUiThread(() -> aviso("Revisa correo, contraseña e internet"));
                }
            });
        }), ancho());
    }

    private void cargar() {
        fondo.execute(() -> {
            try {
                List<Api.Tarea> todas = api.listar();
                runOnUiThread(() -> pintar(todas));
            } catch (Exception e) {
                runOnUiThread(this::pintarError);
            }
        });
    }

    private void pintarError() {
        raiz.removeAllViews();
        raiz.addView(etiqueta("Sin conexión", 16, Color.WHITE));
        raiz.addView(boton("Reintentar", v -> cargar()), ancho());
        raiz.addView(boton("Cerrar sesión", v -> { Avisos.cancelar(this); api.salir(); pantallaEntrar(); }), ancho());
    }

    private void pintar(List<Api.Tarea> todas) {
        String hoy = LocalDate.now().toString();
        List<Api.Tarea> atrasadas = new ArrayList<>(), deHoy = new ArrayList<>(), eventos = new ArrayList<>();
        for (Api.Tarea t : todas) {
            if (t.fecha.isEmpty()) continue;
            if ("evento".equals(t.tipo)) {
                if (t.fecha.equals(hoy) && !"tachado".equals(t.estado)) eventos.add(t);
                continue;
            }
            if (t.fecha.equals(hoy)) deHoy.add(t);
            else if (t.fecha.compareTo(hoy) < 0 && "pendiente".equals(t.estado)) atrasadas.add(t);
        }
        eventos.sort((a, b) -> a.hora.compareTo(b.hora));
        atrasadas.sort((a, b) -> a.fecha.compareTo(b.fecha));
        deHoy.sort((a, b) -> {
            int p = Integer.compare(peso(a.estado), peso(b.estado));
            if (p == 0) p = Integer.compare(rango(a.prioridad), rango(b.prioridad));
            return p != 0 ? p : Long.compare(a.creado, b.creado);
        });

        raiz.removeAllViews();
        raiz.addView(etiqueta("Hoy", 18, Color.WHITE));
        EditText nuevo = new EditText(this);
        nuevo.setHint("Nuevo pendiente");
        nuevo.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        raiz.addView(nuevo, ancho());
        raiz.addView(boton("Agregar", v -> {
            String s = nuevo.getText().toString().trim();
            if (s.isEmpty()) return;
            accion(() -> api.crear(s, hoy));
        }), ancho());

        if (!eventos.isEmpty()) {
            raiz.addView(etiqueta("Reuniones", 13, AZUL), ancho());
            for (Api.Tarea t : eventos) {
                String h = t.hora.isEmpty() ? "Todo el día" : t.hora;
                raiz.addView(etiqueta(h + " · " + (t.area.isEmpty() ? "" : t.area + " · ") + t.texto, 14, AZUL), ancho());
            }
            raiz.addView(etiqueta("Pendientes", 13, Color.LTGRAY), ancho());
        }
        if (!atrasadas.isEmpty()) {
            raiz.addView(etiqueta("Atrasados", 13, ROJO), ancho());
            for (Api.Tarea t : atrasadas) fila(t);
        }
        if (deHoy.isEmpty() && atrasadas.isEmpty() && eventos.isEmpty()) raiz.addView(etiqueta("Nada para hoy", 14, Color.LTGRAY), ancho());
        for (Api.Tarea t : deHoy) fila(t);

        raiz.addView(boton("Actualizar", v -> cargar()), ancho());
        raiz.addView(boton("Cerrar sesión", v -> { Avisos.cancelar(this); api.salir(); pantallaEntrar(); }), ancho());
    }

    private static int rango(String p) {
        return "alta".equals(p) ? 0 : "media".equals(p) ? 1 : "baja".equals(p) ? 2 : 3;
    }

    private static int peso(String estado) {
        return "pendiente".equals(estado) ? 0 : "hecho".equals(estado) ? 1 : 2;
    }

    private void fila(Api.Tarea t) {
        boolean hecho = "hecho".equals(t.estado), tachado = "tachado".equals(t.estado);
        String sem = "alta".equals(t.prioridad) ? "🔴 " : "media".equals(t.prioridad) ? "🟡 " : "baja".equals(t.prioridad) ? "🟢 " : "";
        TextView txt = etiqueta(sem + (t.area.isEmpty() ? "" : t.area + " · ") + (t.municipio.isEmpty() ? "" : t.municipio + " · ") + t.texto, 15, hecho || tachado ? Color.GRAY : Color.WHITE);
        if (tachado) txt.setPaintFlags(txt.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
        LinearLayout.LayoutParams p = ancho();
        p.topMargin = dp(12);
        raiz.addView(txt, p);

        LinearLayout botones = new LinearLayout(this);
        botones.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams m = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        Button c = boton(hecho ? "Quitar ✓" : "✓", v -> accion(() -> api.cambiarEstado(t.id, hecho ? "pendiente" : "hecho")));
        c.setTextColor(VERDE);
        Button x = boton(tachado ? "Destachar" : "Tachar", v -> accion(() -> api.cambiarEstado(t.id, tachado ? "pendiente" : "tachado")));
        x.setTextColor(ROJO);
        botones.addView(c, m);
        botones.addView(x, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        raiz.addView(botones, ancho());
    }

    private interface Accion { void correr() throws Exception; }

    private void accion(Accion a) {
        fondo.execute(() -> {
            try {
                a.correr();
                runOnUiThread(this::cargar);
            } catch (Exception e) {
                runOnUiThread(() -> aviso("No se guardó. Revisa la conexión"));
            }
        });
    }
}
