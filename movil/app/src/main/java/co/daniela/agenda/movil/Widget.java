package co.daniela.agenda.movil;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.widget.RemoteViews;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Widget de la pantalla de inicio: reuniones y pendientes de los próximos días. */
public class Widget extends AppWidgetProvider {
    static final String ACTUALIZAR = "co.daniela.agenda.movil.ACTUALIZAR";
    static final String PREFS = "widget";
    private static final Locale ES = new Locale("es", "CO");

    @Override public void onUpdate(Context c, AppWidgetManager m, int[] ids) { refrescar(c, goAsync()); }

    @Override public void onReceive(Context c, Intent i) {
        if (ACTUALIZAR.equals(i.getAction())) { refrescar(c, goAsync()); return; }
        super.onReceive(c, i);
    }

    static void refrescar(Context c, PendingResult fin) {
        new Thread(() -> {
            try {
                Api api = new Api(c.getSharedPreferences("sesion", Context.MODE_PRIVATE));
                if (!api.conSesion()) pintar(c, null, "Abre la app Agenda e inicia sesión.");
                else pintar(c, api.listar(), null);
            } catch (Exception e) {
                pintar(c, null, "Sin conexión. Toca ↻ para reintentar.");
            } finally {
                if (fin != null) fin.finish();
            }
        }).start();
    }

    static String hora(String h) {
        if (h == null || h.isEmpty()) return "Todo el día";
        LocalTime t = LocalTime.parse(h);
        int x = t.getHour() % 12 == 0 ? 12 : t.getHour() % 12;
        return x + ":" + String.format("%02d", t.getMinute()) + (t.getHour() < 12 ? " a. m." : " p. m.");
    }

    private static void con(SpannableStringBuilder sb, String texto, Object... estilos) {
        int ini = sb.length();
        sb.append(texto);
        for (Object e : estilos) sb.setSpan(e, ini, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private static int rango(String p) { return "alta".equals(p) ? 0 : "media".equals(p) ? 1 : "baja".equals(p) ? 2 : 3; }

    private static String nombreDia(LocalDate d, LocalDate hoy) {
        if (d.equals(hoy)) return "Hoy";
        if (d.equals(hoy.plusDays(1))) return "Mañana";
        String s = d.format(DateTimeFormatter.ofPattern("EEEE d", ES));
        return s.substring(0, 1).toUpperCase(ES) + s.substring(1);
    }

    static void pintar(Context c, List<Api.Tarea> todas, String error) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int dias = p.getInt("dias", 3);
        int opacidad = p.getInt("opacidad", 100);
        boolean oscuro = p.getBoolean("oscuro", false);
        int tinta = oscuro ? Color.rgb(246, 236, 232) : Color.rgb(30, 42, 47);
        int gris = oscuro ? Color.rgb(167, 180, 184) : Color.rgb(95, 107, 111);
        int verde = oscuro ? Color.rgb(155, 228, 209) : Color.rgb(47, 125, 116);
        int rojo = oscuro ? Color.rgb(255, 138, 127) : Color.rgb(209, 69, 59);
        int ambar = oscuro ? Color.rgb(242, 193, 78) : Color.rgb(217, 154, 18);
        int verdeSem = oscuro ? Color.rgb(127, 211, 154) : Color.rgb(63, 154, 90);

        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget);
        v.setImageViewResource(R.id.fondoImg, oscuro ? R.drawable.fondo_oscuro : R.drawable.fondo);
        v.setInt(R.id.fondoImg, "setImageAlpha", Math.round(opacidad * 2.55f));
        v.setTextColor(R.id.titulo, tinta);
        v.setTextColor(R.id.pie, gris);
        v.setTextColor(R.id.mas, verde);
        v.setTextColor(R.id.refrescar, verde);

        LocalDate hoyF = LocalDate.now();
        String hoy = hoyF.toString();
        String titulo = dias == 1 ? hoyF.format(DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", ES)) : dias >= 7 ? "Esta semana" : "Próximos días";
        v.setTextViewText(R.id.titulo, titulo.substring(0, 1).toUpperCase(ES) + titulo.substring(1));

        SpannableStringBuilder sb = new SpannableStringBuilder();
        if (error != null) {
            con(sb, error, new ForegroundColorSpan(gris));
        } else {
            int atrasados = 0;
            for (Api.Tarea t : todas)
                if (!"evento".equals(t.tipo) && "pendiente".equals(t.estado) && !t.fecha.isEmpty() && t.fecha.compareTo(hoy) < 0) atrasados++;
            if (atrasados > 0) con(sb, atrasados + (atrasados == 1 ? " pendiente atrasado\n" : " pendientes atrasados\n"), new StyleSpan(Typeface.BOLD), new ForegroundColorSpan(rojo));
            boolean algo = false;
            for (int k = 0; k < dias; k++) {
                LocalDate dia = hoyF.plusDays(k);
                String f = dia.toString();
                List<Api.Tarea> evs = new ArrayList<>(), pend = new ArrayList<>();
                for (Api.Tarea t : todas) {
                    if (!f.equals(t.fecha)) continue;
                    if ("evento".equals(t.tipo)) { if (!"tachado".equals(t.estado)) evs.add(t); }
                    else if ("pendiente".equals(t.estado)) pend.add(t);
                }
                if (evs.isEmpty() && pend.isEmpty() && k > 0) continue;
                algo = true;
                evs.sort((a, b) -> a.hora.compareTo(b.hora));
                pend.sort((a, b) -> Integer.compare(rango(a.prioridad), rango(b.prioridad)));
                if (dias > 1) con(sb, (sb.length() > 0 ? "\n" : "") + nombreDia(dia, hoyF).toUpperCase(ES) + "\n", new StyleSpan(Typeface.BOLD), new ForegroundColorSpan(gris), new RelativeSizeSpan(0.82f));
                if (evs.isEmpty() && pend.isEmpty()) { con(sb, "Sin reuniones ni pendientes\n", new ForegroundColorSpan(gris)); continue; }
                int lim = dias == 1 ? 5 : 3, n = 0;
                for (Api.Tarea t : evs) {
                    if (n++ == lim) { con(sb, "+" + (evs.size() - lim) + " reuniones más\n", new ForegroundColorSpan(gris)); break; }
                    con(sb, hora(t.hora) + "  ", new StyleSpan(Typeface.BOLD), new ForegroundColorSpan(verde));
                    String et = (t.area.isEmpty() ? "" : t.area) + (t.municipio.isEmpty() ? "" : " · " + t.municipio);
                    if (!et.isEmpty()) con(sb, et + "  ", new ForegroundColorSpan(gris));
                    con(sb, t.texto + "\n", new ForegroundColorSpan(tinta));
                }
                n = 0;
                for (Api.Tarea t : pend) {
                    if (n++ == lim) { con(sb, "+" + (pend.size() - lim) + " pendientes más\n", new ForegroundColorSpan(gris)); break; }
                    int col = "alta".equals(t.prioridad) ? rojo : "media".equals(t.prioridad) ? ambar : "baja".equals(t.prioridad) ? verdeSem : gris;
                    con(sb, t.prioridad.isEmpty() ? "○ " : "● ", new ForegroundColorSpan(col));
                    con(sb, t.texto, new ForegroundColorSpan(tinta));
                    if (!t.municipio.isEmpty()) con(sb, "  " + t.municipio, new ForegroundColorSpan(gris));
                    sb.append("\n");
                }
            }
            if (!algo && atrasados == 0) con(sb, "Nada pendiente en estos días.", new ForegroundColorSpan(gris));
        }
        v.setTextViewText(R.id.cuerpo, sb);
        v.setTextViewText(R.id.pie, "Actualizado " + hora(LocalTime.now().withSecond(0).withNano(0).toString()));
        Intent abrir = new Intent(c, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        v.setOnClickPendingIntent(R.id.raiz, PendingIntent.getActivity(c, 0, abrir, PendingIntent.FLAG_IMMUTABLE));
        Intent agregar = new Intent(c, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("agregar", true);
        v.setOnClickPendingIntent(R.id.mas, PendingIntent.getActivity(c, 2, agregar, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        Intent r = new Intent(c, Widget.class).setAction(ACTUALIZAR);
        v.setOnClickPendingIntent(R.id.refrescar, PendingIntent.getBroadcast(c, 1, r, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        AppWidgetManager.getInstance(c).updateAppWidget(new ComponentName(c, Widget.class), v);
    }
}
