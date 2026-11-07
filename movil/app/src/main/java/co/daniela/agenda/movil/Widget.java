package co.daniela.agenda.movil;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.widget.RemoteViews;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Widget de la pantalla de inicio: reuniones y pendientes de hoy. */
public class Widget extends AppWidgetProvider {
    static final String ACTUALIZAR = "co.daniela.agenda.movil.ACTUALIZAR";
    private static final int TINTA = Color.rgb(30, 42, 47);
    private static final int VERDE = Color.rgb(62, 159, 147);
    private static final int GRIS = Color.rgb(95, 107, 111);
    private static final int ROJO = Color.rgb(209, 69, 59);
    private static final int AMBAR = Color.rgb(217, 154, 18);
    private static final int VERDE_SEM = Color.rgb(63, 154, 90);
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

    static void pintar(Context c, List<Api.Tarea> todas, String error) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget);
        LocalDate hoyF = LocalDate.now();
        String hoy = hoyF.toString();
        String titulo = hoyF.format(DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", ES));
        v.setTextViewText(R.id.titulo, titulo.substring(0, 1).toUpperCase(ES) + titulo.substring(1));
        SpannableStringBuilder sb = new SpannableStringBuilder();
        if (error != null) {
            con(sb, error, new ForegroundColorSpan(GRIS));
        } else {
            List<Api.Tarea> evs = new ArrayList<>(), pend = new ArrayList<>();
            int atrasados = 0;
            for (Api.Tarea t : todas) {
                boolean ev = "evento".equals(t.tipo);
                if (ev && hoy.equals(t.fecha) && !"tachado".equals(t.estado)) evs.add(t);
                else if (!ev && "pendiente".equals(t.estado) && !t.fecha.isEmpty()) {
                    if (hoy.equals(t.fecha)) pend.add(t);
                    else if (t.fecha.compareTo(hoy) < 0) atrasados++;
                }
            }
            evs.sort((a, b) -> a.hora.compareTo(b.hora));
            pend.sort((a, b) -> Integer.compare(rango(a.prioridad), rango(b.prioridad)));
            if (evs.isEmpty() && pend.isEmpty() && atrasados == 0) con(sb, "Día libre: sin reuniones ni pendientes.", new ForegroundColorSpan(GRIS));
            int n = 0;
            for (Api.Tarea t : evs) {
                if (n++ == 4) { con(sb, "+" + (evs.size() - 4) + " reuniones más\n", new ForegroundColorSpan(GRIS)); break; }
                con(sb, hora(t.hora) + "  ", new StyleSpan(Typeface.BOLD), new ForegroundColorSpan(VERDE));
                String etiqueta = (t.area.isEmpty() ? "" : t.area) + (t.municipio.isEmpty() ? "" : " · " + t.municipio);
                if (!etiqueta.isEmpty()) con(sb, etiqueta + "  ", new ForegroundColorSpan(GRIS));
                con(sb, t.texto + "\n", new ForegroundColorSpan(TINTA));
            }
            n = 0;
            for (Api.Tarea t : pend) {
                if (n++ == 5) { con(sb, "+" + (pend.size() - 5) + " pendientes más\n", new ForegroundColorSpan(GRIS)); break; }
                int col = "alta".equals(t.prioridad) ? ROJO : "media".equals(t.prioridad) ? AMBAR : "baja".equals(t.prioridad) ? VERDE_SEM : GRIS;
                con(sb, (t.prioridad.isEmpty() ? "○ " : "● "), new ForegroundColorSpan(col));
                con(sb, t.texto, new ForegroundColorSpan(TINTA));
                if (!t.municipio.isEmpty()) con(sb, "  " + t.municipio, new ForegroundColorSpan(GRIS));
                sb.append("\n");
            }
            if (atrasados > 0) con(sb, atrasados + (atrasados == 1 ? " pendiente atrasado" : " pendientes atrasados"), new StyleSpan(Typeface.BOLD), new ForegroundColorSpan(ROJO));
        }
        v.setTextViewText(R.id.cuerpo, sb);
        v.setTextViewText(R.id.pie, "Actualizado " + hora(LocalTime.now().withSecond(0).withNano(0).toString()) + " · toca para abrir");
        Intent abrir = new Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.AGENDA_URL));
        v.setOnClickPendingIntent(R.id.raiz, PendingIntent.getActivity(c, 0, abrir, PendingIntent.FLAG_IMMUTABLE));
        Intent r = new Intent(c, Widget.class).setAction(ACTUALIZAR);
        v.setOnClickPendingIntent(R.id.refrescar, PendingIntent.getBroadcast(c, 1, r, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        AppWidgetManager.getInstance(c).updateAppWidget(new ComponentName(c, Widget.class), v);
    }
}
