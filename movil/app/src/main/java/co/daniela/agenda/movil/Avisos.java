package co.daniela.agenda.movil;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;

/** Cada 15 minutos: actualiza el widget y avisa antes de reuniones y por urgentes. */
public class Avisos extends JobService {
    private static final int TRABAJO = 1;
    private static final String CANAL = "agenda";

    static void programar(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js == null || js.getPendingJob(TRABAJO) != null) return;
        JobInfo info = new JobInfo.Builder(TRABAJO, new ComponentName(c, Avisos.class))
            .setPeriodic(15 * 60 * 1000L)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .build();
        try { js.schedule(info); } catch (RuntimeException ignorado) { }
    }

    static void cancelar(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js != null) js.cancel(TRABAJO);
    }

    @Override public boolean onStartJob(JobParameters p) {
        new Thread(() -> {
            try { revisar(this); } catch (Exception ignorado) { }
            jobFinished(p, false);
        }).start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters p) { return true; }

    static void revisar(Context c) throws Exception {
        Api api = new Api(c.getSharedPreferences("sesion", Context.MODE_PRIVATE));
        if (!api.conSesion()) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CANAL, "Avisos de la agenda", NotificationManager.IMPORTANCE_HIGH));

        String hoy = LocalDate.now().toString();
        LocalTime ahora = LocalTime.now();
        SharedPreferences avisados = c.getSharedPreferences("avisos", Context.MODE_PRIVATE);
        SharedPreferences.Editor ed = avisados.edit();
        boolean diaNuevo = !hoy.equals(avisados.getString("dia", ""));
        if (diaNuevo) { ed.clear(); ed.putString("dia", hoy); }

        org.json.JSONArray docs = api.descargar();
        c.getSharedPreferences(Widget.PREFS, Context.MODE_PRIVATE).edit().putString("cache", docs.toString()).putLong("cacheHora", System.currentTimeMillis()).apply();
        java.util.List<Api.Tarea> lista = Api.leer(docs);
        Widget.pintar(c, lista, null, null);
        for (Api.Tarea t : lista) {
            if (!"pendiente".equals(t.estado)) continue;
            String clave = null, titulo = null;
            if ("evento".equals(t.tipo)) {
                if (!hoy.equals(t.fecha) || t.hora.isEmpty()) continue;
                long faltan = Duration.between(ahora, LocalTime.parse(t.hora)).toMinutes();
                if (faltan < 0 || faltan > 30) continue;
                clave = "r" + t.id;
                titulo = "Reunión a las " + t.hora;
            } else if ("alta".equals(t.prioridad) && !t.fecha.isEmpty() && t.fecha.compareTo(hoy) <= 0 && ahora.getHour() >= 8) {
                clave = "u" + t.id;
                titulo = t.fecha.equals(hoy) ? "Urgente para hoy" : "Urgente atrasado";
            }
            if (clave == null || (!diaNuevo && avisados.contains(clave))) continue;
            PendingIntent abrir = PendingIntent.getActivity(c, 0, new Intent(c, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
            Notification n = new Notification.Builder(c, CANAL)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle(titulo)
                .setContentText(t.texto)
                .setContentIntent(abrir)
                .setAutoCancel(true)
                .build();
            nm.notify(clave.hashCode(), n);
            ed.putBoolean(clave, true);
        }
        ed.apply();
    }
}
