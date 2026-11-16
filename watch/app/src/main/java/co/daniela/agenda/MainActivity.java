package co.daniela.agenda;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Hoy y atrasados en el reloj: próxima reunión, tarjetas por área, detalle, chulear, tachar y agregar. */
public class MainActivity extends Activity {
    // Paleta de la agenda web en modo oscuro.
    private static final int MENTA = 0xFF9BE4D1, DURAZNO = 0xFFFFB199, ROSA = 0xFFFCE1D9;
    private static final int TINTA = 0xFFF6ECE8, GRIS = 0xFFA7B4B8, TENUE = 0xFF5F6B6F;
    private static final int TARJETA = 0xFF1B2529, OSCURO = 0xFF1E2A2F;
    private static final int ROJO = 0xFFFF8A7F, AMARILLO = 0xFFF2C14E, VERDE = 0xFF7FD39A;

    private static final int LISTA = 0, DETALLE = 1, NUEVO = 2, ENTRAR = 3, CALENDARIO = 4;
    private static final String URL_APK = "https://dani-vergaram.github.io/agenda/agenda-reloj.apk";
    private static final String ACCION_INSTALAR = "co.daniela.agenda.INSTALAR";
    private static final Locale ES = new Locale("es", "CO");
    private static final DateTimeFormatter DIA_LARGO = DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", ES);
    private static final DateTimeFormatter DIA_CORTO = DateTimeFormatter.ofPattern("d MMM", ES);
    private static final DateTimeFormatter DIA_SEMANA = DateTimeFormatter.ofPattern("EEE d MMM", ES);
    private static final DateTimeFormatter MES = DateTimeFormatter.ofPattern("MMMM yyyy", ES);

    private final ExecutorService fondo = Executors.newSingleThreadExecutor();
    private Api api;
    private ScrollView scroll;
    private LinearLayout raiz;
    private List<Api.Tarea> ultimas; // última lista leída; null mientras no haya cargado
    private int vista = LISTA;
    private int posicion = -1; // desplazamiento de la lista para restaurarlo al volver
    private int origen = LISTA; // pantalla desde la que se abrió el detalle
    private YearMonth mes;
    private LocalDate diaSel;
    private boolean sinConexion; // la última lectura falló y se muestran los datos guardados
    private final Map<String, Integer> coloresEnt = new HashMap<>(); // colores de las entidades, como en la web
    private final List<String> nombresEnt = new ArrayList<>();

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        api = new Api(getSharedPreferences("sesion", Context.MODE_PRIVATE));
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.BLACK);
        scroll.setVerticalScrollBarEnabled(false);
        // Desplazamiento con el bisel o la corona.
        scroll.setOnGenericMotionListener((v, ev) -> {
            if (ev.getAction() != MotionEvent.ACTION_SCROLL || !ev.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) return false;
            float paso = -ev.getAxisValue(MotionEvent.AXIS_SCROLL) * ViewConfiguration.get(this).getScaledVerticalScrollFactor();
            v.scrollBy(0, Math.round(paso));
            return true;
        });
        raiz = new LinearLayout(this);
        raiz.setOrientation(LinearLayout.VERTICAL);
        raiz.setGravity(Gravity.CENTER_HORIZONTAL);
        raiz.setPadding(dp(14), dp(26), dp(14), dp(56)); // márgenes para la pantalla redonda
        scroll.addView(raiz);
        setContentView(scroll);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { "android.permission.POST_NOTIFICATIONS" }, 1);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (!api.conSesion()) {
            if (vista != ENTRAR) pantallaEntrar();
        } else {
            Avisos.programar(this);
            if (vista == ENTRAR) vista = LISTA;
            leerEntidades();
            if (vista == LISTA && ultimas == null) {
                ultimas = api.guardada(); // lo último que se leyó, para verlo sin conexión
                if (ultimas != null) pintar(); else pintarMensaje("Cargando…");
            }
            cargar();
        }
        scroll.requestFocus();
    }

    @Override protected void onDestroy() { fondo.shutdown(); super.onDestroy(); }

    @Override public void onBackPressed() {
        if (vista == DETALLE || vista == NUEVO || vista == CALENDARIO) volver(); else super.onBackPressed();
    }

    /** Respuesta del sistema al pedirle instalar una versión nueva. */
    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        if (!ACCION_INSTALAR.equals(i.getAction())) return;
        int estado = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (estado == PackageInstaller.STATUS_SUCCESS) return;
        try {
            Intent confirmar = i.getParcelableExtra(Intent.EXTRA_INTENT);
            if (estado != PackageInstaller.STATUS_PENDING_USER_ACTION || confirmar == null) throw new IllegalStateException();
            startActivity(confirmar); // el reloj pide confirmar la instalación
        } catch (RuntimeException e) {
            avisoLargo("El reloj no permitió instalar. Usa Bugjaeger");
        }
    }

    // ---------- Piezas de diseño ----------

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private TextView texto(CharSequence s, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(sp); t.setTextColor(color);
        return t;
    }

    private TextView centrado(CharSequence s, float sp, int color) {
        TextView t = texto(s, sp, color);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private TextView negrita(TextView t) { t.setTypeface(Typeface.DEFAULT_BOLD); return t; }

    /** Encabezado de sección: pequeño, en mayúsculas y espaciado. */
    private TextView seccion(String s, int color) {
        TextView t = negrita(centrado(s.toUpperCase(ES), 11, color));
        t.setLetterSpacing(0.1f);
        return t;
    }

    private GradientDrawable redondo(int color, float radioDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radioDp));
        return g;
    }

    /** Agrega la onda al tocar, recortada a la misma forma redondeada. */
    private Drawable conOnda(Drawable d, float radioDp) {
        return new RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), d, redondo(Color.WHITE, radioDp));
    }

    /** Tarjeta oscura con una franja del color del área a la izquierda. */
    private Drawable tarjeta(int franja) {
        LayerDrawable capas = new LayerDrawable(new Drawable[] { redondo(franja, 16), redondo(TARJETA, 16) });
        capas.setLayerInset(1, dp(4), 0, 0, 0);
        return conOnda(capas, 16);
    }

    private TextView pastilla(String s, int relleno, int colorTexto, View.OnClickListener l) {
        TextView t = negrita(centrado(s, 14, colorTexto));
        t.setBackground(conOnda(redondo(relleno, 24), 24));
        t.setMinHeight(dp(44));
        t.setPadding(dp(16), dp(8), dp(16), dp(8));
        t.setOnClickListener(l);
        return t;
    }

    private TextView contorno(String s, int color, View.OnClickListener l) {
        TextView t = centrado(s, 14, color);
        GradientDrawable g = redondo(Color.TRANSPARENT, 24);
        g.setStroke(dp(1.5f), color);
        t.setBackground(conOnda(g, 24));
        t.setMinHeight(dp(44));
        t.setPadding(dp(16), dp(8), dp(16), dp(8));
        t.setOnClickListener(l);
        return t;
    }

    private EditText campo(String pista, int tipo) {
        EditText e = new EditText(this);
        e.setHint(pista);
        e.setInputType(tipo);
        e.setTextSize(14);
        e.setTextColor(TINTA);
        e.setHintTextColor(GRIS);
        e.setBackground(redondo(TARJETA, 16));
        e.setPadding(dp(14), dp(10), dp(14), dp(10));
        e.setMinHeight(dp(44));
        return e;
    }

    private LinearLayout.LayoutParams ancho(int arribaDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(arribaDp);
        return p;
    }

    private LinearLayout.LayoutParams justo(int arribaDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(arribaDp);
        return p;
    }

    private void aviso(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private void avisoLargo(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    /** Cuándo se leyó la agenda por última vez: "las 15:40" o "15 nov, 15:40". */
    private String horaGuardada() {
        LocalDateTime m = LocalDateTime.ofInstant(Instant.ofEpochMilli(api.horaGuardada()), ZoneId.systemDefault());
        String h = m.toLocalTime().toString().substring(0, 5);
        return m.toLocalDate().equals(LocalDate.now()) ? "las " + h : fechaCorta(m.toLocalDate().toString()) + ", " + h;
    }

    /** Color de la entidad según la agenda web; si aún no se ha leído, los de siempre. */
    private int colorArea(String area) {
        Integer propio = coloresEnt.get(area);
        return propio != null ? propio : "CAR".equals(area) ? MENTA : "UAESP".equals(area) ? DURAZNO : GRIS;
    }

    private void leerEntidades() {
        coloresEnt.clear();
        nombresEnt.clear();
        for (Api.Entidad e : api.entidades()) {
            if (e.nombre.isEmpty()) continue;
            nombresEnt.add(e.nombre);
            try { coloresEnt.put(e.nombre, Color.parseColor(e.color)); } catch (RuntimeException sinColor) { }
        }
    }

    /** Texto oscuro o claro, según cuál se lea mejor sobre ese color. */
    private static int sobre(int color) {
        double luz = (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255;
        return luz > 0.5 ? OSCURO : TINTA;
    }

    private static int colorPrioridad(String p) {
        return "alta".equals(p) ? ROJO : "media".equals(p) ? AMARILLO : "baja".equals(p) ? VERDE : 0;
    }

    private static LocalTime hora(String s) {
        if (s == null || s.isEmpty()) return null;
        try { return LocalTime.parse(s.length() == 4 ? "0" + s : s); } catch (RuntimeException e) { return null; }
    }

    /** Hora de fin de una reunión; si no la tiene, una hora después del inicio. */
    private static LocalTime fin(Api.Tarea t, LocalTime inicio) {
        LocalTime f = hora(t.horaFin);
        if (f != null && f.isAfter(inicio)) return f;
        return inicio.getHour() >= 23 ? LocalTime.MAX : inicio.plusHours(1);
    }

    private static String fechaCorta(String iso) {
        try { return LocalDate.parse(iso).format(DIA_CORTO).replace(".", ""); } catch (RuntimeException e) { return iso; }
    }

    private static String mayuscula(String s) { return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(ES) + s.substring(1); }

    /** "Hoy", "Mañana" o "Mié 18 nov". */
    private static String diaRelativo(String iso, LocalDate hoy) {
        try {
            LocalDate d = LocalDate.parse(iso);
            if (d.equals(hoy)) return "Hoy";
            if (d.equals(hoy.plusDays(1))) return "Mañana";
            return mayuscula(d.format(DIA_SEMANA).replace(".", ""));
        } catch (RuntimeException e) { return iso; }
    }

    private static final Comparator<Api.Tarea> POR_HORA = (a, b) -> {
        int f = a.fecha.compareTo(b.fecha);
        return f != 0 ? f : a.hora.compareTo(b.hora);
    };

    private static final Comparator<Api.Tarea> POR_URGENCIA = (a, b) -> {
        int p = Integer.compare(peso(a.estado), peso(b.estado));
        if (p == 0) p = Integer.compare(rango(a.prioridad), rango(b.prioridad));
        return p != 0 ? p : Long.compare(a.creado, b.creado);
    };

    private static String falta(LocalTime ahora, LocalTime inicio) {
        long min = (Duration.between(ahora, inicio).getSeconds() + 59) / 60;
        if (min < 60) return "en " + Math.max(min, 1) + " min";
        long h = min / 60, m = min % 60;
        return "en " + h + " h" + (m > 0 ? " " + m + " min" : "");
    }

    // ---------- Pantallas ----------

    private void limpiar(int nuevaVista) {
        vista = nuevaVista;
        raiz.removeAllViews();
    }

    private void pantallaEntrar() {
        limpiar(ENTRAR);
        ultimas = null;
        raiz.addView(negrita(centrado("Agenda", 20, MENTA)));
        raiz.addView(centrado("Inicia sesión", 12, GRIS));
        EditText correo = campo("Correo", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        EditText clave = campo("Contraseña", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        raiz.addView(correo, ancho(10));
        raiz.addView(clave, ancho(6));
        raiz.addView(pastilla("Entrar", MENTA, OSCURO, v -> {
            String c = correo.getText().toString().trim(), k = clave.getText().toString();
            fondo.execute(() -> {
                try {
                    api.entrar(c, k);
                    Avisos.programar(this);
                    runOnUiThread(() -> { vista = LISTA; pintarMensaje("Cargando…"); cargar(); });
                } catch (Exception e) {
                    runOnUiThread(() -> aviso("Revisa correo, contraseña e internet"));
                }
            });
        }), ancho(10));
    }

    private void cargar() {
        fondo.execute(() -> {
            try {
                List<Api.Tarea> todas = api.listar();
                runOnUiThread(() -> { ultimas = todas; sinConexion = false; leerEntidades(); if (vista == LISTA) pintar(); else if (vista == CALENDARIO) pantallaCalendario(); });
            } catch (Exception e) {
                List<Api.Tarea> copia = api.guardada(); // incluye los cambios hechos sin conexión
                runOnUiThread(() -> {
                    sinConexion = true;
                    if (copia != null) ultimas = copia;
                    if (vista == CALENDARIO && ultimas != null) { pantallaCalendario(); return; }
                    if (vista != LISTA) return;
                    if (ultimas == null) pintarError(); else pintar();
                });
            }
        });
    }

    private void pintarMensaje(String s) {
        limpiar(LISTA);
        raiz.addView(negrita(centrado("Agenda", 20, MENTA)), ancho(30));
        raiz.addView(centrado(s, 13, GRIS), ancho(6));
    }

    private void pintarError() {
        limpiar(LISTA);
        raiz.addView(negrita(centrado("Sin conexión", 16, TINTA)), ancho(14));
        raiz.addView(centrado("No se pudo leer la agenda", 12, GRIS), ancho(2));
        raiz.addView(pastilla("Reintentar", MENTA, OSCURO, v -> { pintarMensaje("Cargando…"); cargar(); }), ancho(12));
        raiz.addView(cerrarSesion(), justo(6));
    }

    private void salir() {
        Avisos.cancelar(this);
        api.salir();
        pantallaEntrar();
    }

    /** Pide confirmar con un segundo toque para no cerrar sesión por accidente. */
    private TextView cerrarSesion() {
        TextView t = centrado("Cerrar sesión", 12, GRIS);
        t.setPadding(dp(16), dp(12), dp(16), dp(12));
        boolean[] confirmado = { false };
        t.setOnClickListener(v -> {
            if (confirmado[0]) { salir(); return; }
            confirmado[0] = true;
            t.setText("Toca otra vez para salir");
            t.setTextColor(ROJO);
        });
        return t;
    }

    private void pintar() {
        LocalDate dia = LocalDate.now();
        String hoy = dia.toString();
        LocalTime ahora = LocalTime.now();
        List<Api.Tarea> atrasadas = new ArrayList<>(), deHoy = new ArrayList<>(), eventos = new ArrayList<>(), futuras = new ArrayList<>();
        for (Api.Tarea t : ultimas) {
            if (t.fecha.isEmpty()) continue;
            if ("evento".equals(t.tipo)) {
                if ("tachado".equals(t.estado)) continue;
                if (t.fecha.equals(hoy)) eventos.add(t);
                else if (t.fecha.compareTo(hoy) > 0) futuras.add(t);
                continue;
            }
            if (t.fecha.equals(hoy)) deHoy.add(t);
            else if (t.fecha.compareTo(hoy) < 0 && "pendiente".equals(t.estado)) atrasadas.add(t);
        }
        eventos.sort(POR_HORA);
        futuras.sort(POR_HORA);
        atrasadas.sort((a, b) -> a.fecha.compareTo(b.fecha));
        deHoy.sort(POR_URGENCIA);

        // Próxima reunión: la primera de hoy que no ha terminado; si no queda ninguna, la siguiente de otro día.
        Api.Tarea proxima = null;
        for (Api.Tarea e : eventos) {
            LocalTime i = hora(e.hora);
            if (i != null && fin(e, i).isAfter(ahora)) { proxima = e; break; }
        }
        if (proxima == null && !futuras.isEmpty()) proxima = futuras.remove(0);
        int porHacer = atrasadas.size();
        for (Api.Tarea t : deHoy) if ("pendiente".equals(t.estado)) porHacer++;

        limpiar(LISTA);
        String fecha = dia.format(DIA_LARGO);
        raiz.addView(centrado(fecha.substring(0, 1).toUpperCase(ES) + fecha.substring(1), 11, GRIS));
        raiz.addView(negrita(centrado("Hoy", 22, TINTA)));
        String resumen = porHacer == 0 ? "Todo al día" : porHacer + (porHacer == 1 ? " pendiente" : " pendientes");
        if (!eventos.isEmpty()) resumen += " · " + eventos.size() + (eventos.size() == 1 ? " reunión" : " reuniones");
        raiz.addView(centrado(resumen, 12, GRIS));
        if (sinConexion) raiz.addView(centrado("Sin conexión · datos de " + horaGuardada(), 11, AMARILLO));
        int cola = api.porEnviar();
        if (cola > 0) raiz.addView(centrado(cola + (cola == 1 ? " cambio por enviar" : " cambios por enviar"), 11, AMARILLO));

        if (proxima != null) raiz.addView(tarjetaProxima(proxima, ahora, dia), ancho(10));

        TextView mas = pastilla("+  Nuevo", TARJETA, MENTA, v -> pantallaNuevo());
        mas.setMinHeight(dp(40));
        raiz.addView(mas, justo(10));
        TextView cal = pastilla("Calendario", TARJETA, ROSA, v -> abrirCalendario());
        cal.setMinHeight(dp(40));
        raiz.addView(cal, justo(6));

        if (eventos.size() > (eventos.contains(proxima) ? 1 : 0)) {
            raiz.addView(seccion("Reuniones de hoy", ROSA), ancho(14));
            for (Api.Tarea e : eventos) if (e != proxima) raiz.addView(tarjetaReunion(e, ""), ancho(6));
        }
        if (!atrasadas.isEmpty()) {
            raiz.addView(seccion("Atrasados · " + atrasadas.size(), ROJO), ancho(14));
            for (Api.Tarea t : atrasadas) raiz.addView(tarjetaTarea(t, true), ancho(6));
        }
        if (!deHoy.isEmpty()) {
            raiz.addView(seccion("Pendientes de hoy", GRIS), ancho(14));
            for (Api.Tarea t : deHoy) raiz.addView(tarjetaTarea(t, false), ancho(6));
        }
        if (deHoy.isEmpty() && atrasadas.isEmpty() && eventos.isEmpty()) {
            raiz.addView(centrado("Nada para hoy", 14, GRIS), ancho(16));
        }

        if (!futuras.isEmpty()) {
            raiz.addView(seccion("Próximas reuniones", ROSA), ancho(14));
            for (int i = 0; i < futuras.size() && i < 4; i++) {
                Api.Tarea e = futuras.get(i);
                raiz.addView(tarjetaReunion(e, diaRelativo(e.fecha, dia) + " · "), ancho(6));
            }
        }

        raiz.addView(contorno("Actualizar", GRIS, v -> { aviso("Actualizando…"); cargar(); }), ancho(18));
        raiz.addView(version(), justo(2));
        raiz.addView(permisoInstalar(), justo(0));
        raiz.addView(cerrarSesion(), justo(0));

        if (posicion >= 0) {
            int y = posicion;
            posicion = -1;
            scroll.post(() -> scroll.scrollTo(0, y));
        }
    }

    private static int rango(String p) {
        return "alta".equals(p) ? 0 : "media".equals(p) ? 1 : "baja".equals(p) ? 2 : 3;
    }

    private static int peso(String estado) {
        return "pendiente".equals(estado) ? 0 : "hecho".equals(estado) ? 1 : 2;
    }

    /** Bloque destacado con la siguiente reunión del día y cuánto falta. */
    private View tarjetaProxima(Api.Tarea e, LocalTime ahora, LocalDate hoy) {
        LocalTime inicio = hora(e.hora);
        boolean esHoy = e.fecha.equals(hoy.toString());
        boolean enCurso = esHoy && inicio != null && !inicio.isAfter(ahora);
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        c.setBackground(conOnda(redondo(ROSA, 22), 22));
        c.setPadding(dp(14), dp(10), dp(14), dp(12));
        c.addView(seccion(enCurso ? "En curso" : "Próxima reunión", 0xB31E2A2F));
        c.addView(negrita(centrado(e.hora.isEmpty() ? "Todo el día" : e.hora, e.hora.isEmpty() ? 18 : 26, OSCURO)));
        String cuando = enCurso ? "hasta las " + fin(e, inicio).toString().substring(0, 5)
            : esHoy && inicio != null ? falta(ahora, inicio) : diaRelativo(e.fecha, hoy);
        c.addView(negrita(centrado(cuando, 12, 0xFFB4472F)));
        TextView titulo = centrado(e.texto, 14, OSCURO);
        titulo.setMaxLines(2);
        titulo.setEllipsize(TextUtils.TruncateAt.END);
        c.addView(titulo, ancho(4));
        if (!e.area.isEmpty()) c.addView(centrado(e.area, 11, 0xB31E2A2F));
        c.setOnClickListener(v -> detalle(e));
        return c;
    }

    private View tarjetaReunion(Api.Tarea e, String prefijo) {
        LocalTime inicio = hora(e.hora);
        int dias = e.fecha.compareTo(LocalDate.now().toString());
        boolean paso = dias < 0 || (dias == 0 && inicio != null && !fin(e, inicio).isAfter(LocalTime.now()));
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(tarjeta(ROSA));
        c.setPadding(dp(16), dp(8), dp(12), dp(8));
        String h = e.hora.isEmpty() ? "Todo el día" : e.hora + (e.horaFin.isEmpty() ? "" : " – " + e.horaFin);
        c.addView(negrita(texto(prefijo + h + (e.area.isEmpty() ? "" : " · " + e.area), 11, ROSA)));
        TextView titulo = texto(e.texto, 14, TINTA);
        titulo.setMaxLines(2);
        titulo.setEllipsize(TextUtils.TruncateAt.END);
        c.addView(titulo);
        if (paso) c.setAlpha(0.5f);
        c.setOnClickListener(v -> detalle(e));
        return c;
    }

    /** Tarjeta de pendiente: toca el texto para ver el detalle y el círculo para chulear. */
    private View tarjetaTarea(Api.Tarea t, boolean atrasada) {
        boolean hecho = "hecho".equals(t.estado), tachado = "tachado".equals(t.estado), cerrado = hecho || tachado;
        int area = cerrado ? TENUE : colorArea(t.area);
        LinearLayout c = new LinearLayout(this);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setBackground(tarjeta(area));
        c.setPadding(dp(16), dp(6), dp(2), dp(6));
        c.setMinimumHeight(dp(50));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        SpannableStringBuilder meta = new SpannableStringBuilder();
        int prio = colorPrioridad(t.prioridad);
        if (prio != 0 && !cerrado) {
            meta.append("● ");
            meta.setSpan(new ForegroundColorSpan(prio), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        meta.append(t.area);
        if (!t.municipio.isEmpty()) meta.append(meta.length() > 0 && !t.area.isEmpty() ? " · " : "").append(t.municipio);
        if (atrasada) {
            int desde = meta.length();
            meta.append(t.area.isEmpty() && t.municipio.isEmpty() ? "" : " · ").append(fechaCorta(t.fecha));
            meta.setSpan(new ForegroundColorSpan(ROJO), desde, meta.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (meta.length() > 0) {
            TextView m = negrita(texto(meta, 11, area));
            m.setMaxLines(1);
            m.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(m);
        }
        TextView txt = texto(t.texto, 14, cerrado ? TENUE : TINTA);
        txt.setMaxLines(3);
        txt.setEllipsize(TextUtils.TruncateAt.END);
        if (tachado) txt.setPaintFlags(txt.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
        col.addView(txt);
        c.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        // Círculo para chulear (o reabrir si ya está hecho o tachado).
        TextView circulo = negrita(centrado(hecho ? "✓" : tachado ? "×" : "", 15, hecho ? OSCURO : GRIS));
        GradientDrawable o = new GradientDrawable();
        o.setShape(GradientDrawable.OVAL);
        if (hecho) o.setColor(MENTA); else { o.setColor(Color.TRANSPARENT); o.setStroke(dp(2), tachado ? TENUE : GRIS); }
        circulo.setBackground(new InsetDrawable(o, dp(8)));
        circulo.setOnClickListener(v -> accion(() -> api.cambiarEstado(t.id, cerrado ? "pendiente" : "hecho")));
        c.addView(circulo, new LinearLayout.LayoutParams(dp(46), dp(46)));

        c.setOnClickListener(v -> detalle(t));
        return c;
    }

    /** Detalle de un pendiente o reunión: nota completa, horario y acciones. */
    private void detalle(Api.Tarea t) {
        if (vista == LISTA) posicion = scroll.getScrollY();
        if (vista == LISTA || vista == CALENDARIO) origen = vista;
        limpiar(DETALLE);
        scroll.scrollTo(0, 0);
        boolean evento = "evento".equals(t.tipo);
        boolean hecho = "hecho".equals(t.estado), tachado = "tachado".equals(t.estado);
        String hoy = LocalDate.now().toString();

        if (!t.area.isEmpty()) {
            TextView chip = negrita(centrado(t.area, 11, sobre(colorArea(t.area))));
            chip.setBackground(redondo(colorArea(t.area), 12));
            chip.setPadding(dp(12), dp(3), dp(12), dp(3));
            raiz.addView(chip, justo(0));
        }
        if (!t.municipio.isEmpty()) raiz.addView(centrado(t.municipio, 12, GRIS), ancho(4));
        raiz.addView(negrita(centrado(t.texto, 16, TINTA)), ancho(6));

        if (evento) {
            String h = t.hora.isEmpty() ? "Todo el día" : t.hora + (t.horaFin.isEmpty() ? "" : " – " + t.horaFin);
            raiz.addView(negrita(centrado(h, 18, ROSA)), ancho(6));
            if (!t.fecha.equals(hoy)) raiz.addView(centrado(fechaCorta(t.fecha), 12, GRIS));
        } else {
            boolean atrasada = t.fecha.compareTo(hoy) < 0 && "pendiente".equals(t.estado);
            String cuando = t.fecha.equals(hoy) ? "Para hoy" : atrasada ? "Venció el " + fechaCorta(t.fecha) : fechaCorta(t.fecha);
            raiz.addView(centrado(cuando, 12, atrasada ? ROJO : GRIS), ancho(6));
            int prio = colorPrioridad(t.prioridad);
            if (prio != 0) raiz.addView(negrita(centrado("● Prioridad " + t.prioridad, 12, prio)), ancho(2));
            if (hecho) raiz.addView(negrita(centrado("✓ Hecho", 12, MENTA)), ancho(2));
            if (tachado) raiz.addView(centrado("Tachado", 12, GRIS), ancho(2));
        }

        if (!t.nota.trim().isEmpty()) {
            TextView nota = texto(t.nota.trim(), 13, TINTA);
            nota.setBackground(redondo(TARJETA, 16));
            nota.setPadding(dp(12), dp(10), dp(12), dp(10));
            nota.setLineSpacing(dp(2), 1f);
            raiz.addView(nota, ancho(10));
        }

        if (!evento) {
            raiz.addView(pastilla(hecho ? "Quitar ✓" : "✓  Hecho", MENTA, OSCURO,
                v -> accion(() -> api.cambiarEstado(t.id, hecho ? "pendiente" : "hecho"))), ancho(12));
            if (!hecho && !tachado) { // aplazar sin abrir la agenda completa
                LocalDate dia = LocalDate.now();
                LocalDate lunes = dia.plusDays(8 - dia.getDayOfWeek().getValue());
                LinearLayout mover = new LinearLayout(this);
                LinearLayout.LayoutParams mitad = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
                mitad.rightMargin = dp(6);
                mover.addView(contorno("Mañana", ROSA, v -> accion(() -> api.mover(t.id, dia.plusDays(1).toString()))), mitad);
                mover.addView(contorno("El lunes", ROSA, v -> accion(() -> api.mover(t.id, lunes.toString()))),
                    new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
                raiz.addView(seccion("Mover a", GRIS), ancho(10));
                raiz.addView(mover, ancho(4));
            }
            raiz.addView(contorno(tachado ? "Destachar" : "Tachar", DURAZNO,
                v -> accion(() -> api.cambiarEstado(t.id, tachado ? "pendiente" : "tachado"))), ancho(6));
        }
        raiz.addView(contorno("Volver", GRIS, v -> volver()), ancho(evento ? 12 : 6));
    }

    // ---------- Calendario ----------

    private void abrirCalendario() {
        if (vista == LISTA) posicion = scroll.getScrollY();
        mes = YearMonth.now();
        diaSel = LocalDate.now();
        scroll.scrollTo(0, 0);
        pantallaCalendario();
    }

    private void cambiarMes(int n) {
        mes = mes.plusMonths(n);
        diaSel = mes.equals(YearMonth.now()) ? LocalDate.now() : mes.atDay(1);
        pantallaCalendario();
    }

    private GradientDrawable ovalo(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        return g;
    }

    /** Mes con puntos en los días con reuniones (rosa) o pendientes (menta), y la lista del día elegido. */
    private void pantallaCalendario() {
        limpiar(CALENDARIO);
        LocalDate hoy = LocalDate.now();
        Map<String, Integer> marcas = new HashMap<>();
        for (Api.Tarea t : ultimas) {
            boolean evento = "evento".equals(t.tipo);
            if (t.fecha.isEmpty() || "tachado".equals(t.estado) || (!evento && !"pendiente".equals(t.estado))) continue;
            marcas.merge(t.fecha, evento ? 1 : 2, (a, b) -> a | b);
        }

        LinearLayout cab = new LinearLayout(this);
        cab.setGravity(Gravity.CENTER);
        cab.addView(flecha("‹", v -> cambiarMes(-1)), new LinearLayout.LayoutParams(dp(40), dp(40)));
        cab.addView(negrita(centrado(mayuscula(mes.format(MES)), 13, TINTA)), new LinearLayout.LayoutParams(dp(100), LinearLayout.LayoutParams.WRAP_CONTENT));
        cab.addView(flecha("›", v -> cambiarMes(1)), new LinearLayout.LayoutParams(dp(40), dp(40)));
        raiz.addView(cab, justo(4));

        LinearLayout semana = new LinearLayout(this);
        for (String s : new String[] { "L", "M", "M", "J", "V", "S", "D" }) {
            semana.addView(centrado(s, 10, GRIS), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        }
        raiz.addView(semana, ancho(2));

        LocalDate d = mes.atDay(1);
        d = d.minusDays(d.getDayOfWeek().getValue() - 1); // la semana empieza en lunes
        while (!d.isAfter(mes.atEndOfMonth())) {
            LinearLayout fila = new LinearLayout(this);
            for (int i = 0; i < 7; i++, d = d.plusDays(1)) {
                fila.addView(celda(d, hoy, marcas.get(d.toString())), new LinearLayout.LayoutParams(0, dp(30), 1));
            }
            raiz.addView(fila, ancho(0));
        }

        String clave = diaSel.toString();
        List<Api.Tarea> reuniones = new ArrayList<>(), tareas = new ArrayList<>();
        for (Api.Tarea t : ultimas) {
            if (!clave.equals(t.fecha)) continue;
            if (!"evento".equals(t.tipo)) tareas.add(t);
            else if (!"tachado".equals(t.estado)) reuniones.add(t);
        }
        reuniones.sort(POR_HORA);
        tareas.sort(POR_URGENCIA);
        raiz.addView(seccion(diaRelativo(clave, hoy), GRIS), ancho(10));
        for (Api.Tarea e : reuniones) raiz.addView(tarjetaReunion(e, ""), ancho(6));
        for (Api.Tarea t : tareas) raiz.addView(tarjetaTarea(t, false), ancho(6));
        if (reuniones.isEmpty() && tareas.isEmpty()) raiz.addView(centrado("Sin nada este día", 13, GRIS), ancho(8));
        raiz.addView(contorno("Volver", GRIS, v -> volver()), ancho(12));
    }

    private TextView flecha(String s, View.OnClickListener l) {
        TextView t = negrita(centrado(s, 22, MENTA));
        t.setBackground(conOnda(redondo(Color.TRANSPARENT, 20), 20));
        t.setOnClickListener(l);
        return t;
    }

    private View celda(LocalDate d, LocalDate hoy, Integer marca) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER);
        if (!YearMonth.from(d).equals(mes)) return c; // día de otro mes: celda vacía
        boolean elegido = d.equals(diaSel), esHoy = d.equals(hoy);
        TextView n = centrado(String.valueOf(d.getDayOfMonth()), 12, elegido ? OSCURO : esHoy ? MENTA : TINTA);
        if (elegido || esHoy) negrita(n);
        if (elegido) n.setBackground(ovalo(MENTA));
        c.addView(n, new LinearLayout.LayoutParams(dp(22), dp(22)));
        LinearLayout puntos = new LinearLayout(this);
        puntos.setGravity(Gravity.CENTER);
        if (marca != null) {
            if ((marca & 1) != 0) puntos.addView(punto(ROSA));
            if ((marca & 2) != 0) puntos.addView(punto(MENTA));
        }
        c.addView(puntos, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(5)));
        c.setOnClickListener(v -> { diaSel = d; pantallaCalendario(); });
        return c;
    }

    private View punto(int color) {
        View p = new View(this);
        p.setBackground(ovalo(color));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(4), dp(4));
        lp.leftMargin = dp(1);
        lp.rightMargin = dp(1);
        p.setLayoutParams(lp);
        return p;
    }

    private void pantallaNuevo() {
        if (vista == LISTA) posicion = scroll.getScrollY();
        limpiar(NUEVO);
        scroll.scrollTo(0, 0);
        raiz.addView(negrita(centrado("Nuevo pendiente", 15, TINTA)), ancho(8));
        EditText nuevo = campo("Escribe o dicta aquí", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        raiz.addView(nuevo, ancho(8));

        // Cada opción cambia al tocarla: para qué día y de qué entidad.
        String[] dias = { "Hoy", "Mañana", "El lunes" };
        int[] dia = { 0 }, area = { -1 }; // -1: sin entidad
        TextView cuando = contorno("Para: " + dias[0], ROSA, null);
        cuando.setOnClickListener(v -> { dia[0] = (dia[0] + 1) % dias.length; cuando.setText("Para: " + dias[dia[0]]); });
        raiz.addView(cuando, ancho(6));
        TextView donde = contorno("Entidad: ninguna", GRIS, null);
        donde.setOnClickListener(v -> {
            area[0] = area[0] + 1 >= nombresEnt.size() ? -1 : area[0] + 1;
            donde.setText("Entidad: " + (area[0] < 0 ? "ninguna" : nombresEnt.get(area[0])));
        });
        if (!nombresEnt.isEmpty()) raiz.addView(donde, ancho(6));

        raiz.addView(pastilla("Agregar", MENTA, OSCURO, v -> {
            String s = nuevo.getText().toString().trim();
            if (s.isEmpty()) return;
            LocalDate hoy = LocalDate.now();
            LocalDate fecha = dia[0] == 0 ? hoy : dia[0] == 1 ? hoy.plusDays(1) : hoy.plusDays(8 - hoy.getDayOfWeek().getValue());
            String entidad = area[0] < 0 ? "" : nombresEnt.get(area[0]);
            accion(() -> api.crear(s, fecha.toString(), entidad));
        }), ancho(8));
        raiz.addView(contorno("Volver", GRIS, v -> volver()), ancho(6));
    }

    private void volver() {
        if (ultimas == null) { pintarMensaje("Cargando…"); cargar(); return; }
        if (vista == DETALLE && origen == CALENDARIO) pantallaCalendario(); else pintar();
        scroll.requestFocus();
    }

    // ---------- Actualización de la app desde el propio reloj ----------

    private static String textoVersion() { return "Versión " + BuildConfig.VERSION_NAME + " · Buscar nueva"; }

    /** Abre el ajuste del reloj donde se permite o se quita que Agenda instale su propia actualización. */
    private TextView permisoInstalar() {
        TextView t = centrado("Permiso de instalación", 12, GRIS);
        t.setPadding(dp(16), dp(8), dp(16), dp(8));
        t.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
            } catch (RuntimeException e) {
                avisoLargo("El reloj no dejó abrir ese ajuste");
            }
        });
        return t;
    }

    private TextView version() {
        TextView t = centrado(textoVersion(), 12, GRIS);
        t.setPadding(dp(16), dp(12), dp(16), dp(8));
        t.setOnClickListener(v -> buscarVersion(t));
        return t;
    }

    /** Descarga la APK publicada y, si es más nueva que la instalada, le pide al reloj instalarla. */
    private void buscarVersion(TextView boton) {
        boton.setText("Buscando…");
        fondo.execute(() -> {
            try {
                File apk = new File(getCacheDir(), "agenda-reloj.apk");
                descargar(URL_APK + "?t=" + System.currentTimeMillis(), apk);
                PackageInfo info = getPackageManager().getPackageArchiveInfo(apk.getPath(), 0);
                if (info == null || !getPackageName().equals(info.packageName)) throw new IOException("APK no válida");
                if (info.getLongVersionCode() <= BuildConfig.VERSION_CODE) {
                    runOnUiThread(() -> { boton.setText(textoVersion()); aviso("Ya tienes la última versión"); });
                    return;
                }
                instalar(apk);
                runOnUiThread(() -> boton.setText("Instalando…"));
            } catch (Exception e) {
                runOnUiThread(() -> { boton.setText(textoVersion()); avisoLargo("No se pudo actualizar. Revisa la conexión"); });
            }
        });
    }

    private static void descargar(String url, File destino) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream(); OutputStream o = new FileOutputStream(destino)) { copiar(in, o); }
        } finally {
            c.disconnect();
        }
    }

    private static void copiar(InputStream in, OutputStream o) throws IOException {
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
    }

    private void instalar(File apk) throws IOException {
        PackageInstaller instalador = getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        p.setAppPackageName(getPackageName());
        if (Build.VERSION.SDK_INT >= 31) p.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        try (PackageInstaller.Session s = instalador.openSession(instalador.createSession(p))) {
            try (InputStream in = new FileInputStream(apk); OutputStream o = s.openWrite("agenda", 0, apk.length())) {
                copiar(in, o);
                s.fsync(o);
            }
            Intent aviso = new Intent(this, MainActivity.class).setAction(ACCION_INSTALAR);
            PendingIntent respuesta = PendingIntent.getActivity(this, 1, aviso, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            s.commit(respuesta.getIntentSender());
        }
    }

    /** Devuelve false si el cambio quedó guardado en el reloj por falta de conexión. */
    private interface Accion { boolean correr() throws Exception; }

    /** Guarda un cambio y vuelve a la lista actualizada. */
    private void accion(Accion a) {
        fondo.execute(() -> {
            try {
                boolean enviado = a.correr();
                runOnUiThread(() -> {
                    if (!enviado) aviso("Guardado en el reloj. Se envía al tener conexión");
                    if (vista == DETALLE && origen == CALENDARIO) vista = CALENDARIO;
                    else if (vista != CALENDARIO) vista = LISTA;
                    cargar();
                });
            } catch (Exception e) {
                runOnUiThread(() -> aviso("No se guardó. Revisa la conexión"));
            }
        });
    }
}
