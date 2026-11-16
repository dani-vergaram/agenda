package co.daniela.agenda;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.Icon;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;

/** Revisa la agenda cada 15 minutos y avisa en el reloj antes de reuniones y por urgentes. */
public class Avisos extends JobService {
    private static final int TRABAJO = 1;
    private static final String CANAL = "agenda";
    private static final int HORA_RESUMEN = 7; // desde las 7:00 a. m. llega el resumen del día

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

        int reuniones = 0, pendientes = 0, urgentes = 0;
        String primera = "";
        for (Api.Tarea t : api.listar()) {
            if (!"pendiente".equals(t.estado)) continue;
            boolean evento = "evento".equals(t.tipo);
            String clave, titulo;
            if (evento) {
                if (!hoy.equals(t.fecha)) continue;
                reuniones++;
                if (t.hora.isEmpty()) continue;
                if (primera.isEmpty() || t.hora.compareTo(primera) < 0) primera = t.hora;
                long faltan;
                try { faltan = Duration.between(ahora, LocalTime.parse(t.hora)).toMinutes(); } catch (RuntimeException horaRara) { continue; }
                if (faltan < 0 || faltan > 30) continue;
                clave = "r" + t.id;
                titulo = "Reunión a las " + t.hora;
            } else {
                if (t.fecha.isEmpty() || t.fecha.compareTo(hoy) > 0) continue;
                pendientes++;
                if (!"alta".equals(t.prioridad)) continue;
                urgentes++;
                if (ahora.getHour() < 8) continue;
                clave = "u" + t.id;
                titulo = t.fecha.equals(hoy) ? "Urgente para hoy" : "Urgente atrasado";
            }
            if (!diaNuevo && avisados.contains(clave)) continue;
            Notification.Builder n = aviso(c, titulo, t.texto);
            if (!evento) { // los urgentes se pueden resolver desde el mismo aviso
                n.addAction(boton(c, "Hecho", "hecho", android.R.drawable.checkbox_on_background, t.id, clave.hashCode()));
                n.addAction(boton(c, "Mañana", "manana", android.R.drawable.ic_menu_recent_history, t.id, clave.hashCode()));
            }
            nm.notify(clave.hashCode(), n.build());
            ed.putBoolean(clave, true);
        }

        // Resumen del día, una sola vez cada mañana.
        boolean yaResumen = !diaNuevo && avisados.contains("resumen");
        if (!yaResumen && ahora.getHour() >= HORA_RESUMEN && ahora.getHour() < 12 && reuniones + pendientes > 0) {
            String texto = reuniones + (reuniones == 1 ? " reunión" : " reuniones") + (primera.isEmpty() ? "" : " (la primera a las " + primera + ")")
                + " · " + pendientes + (pendientes == 1 ? " pendiente" : " pendientes")
                + (urgentes == 0 ? "" : ", " + urgentes + (urgentes == 1 ? " urgente" : " urgentes"));
            nm.notify(1, aviso(c, "Tu día", texto).setStyle(new Notification.BigTextStyle().bigText(texto)).build());
            ed.putBoolean("resumen", true);
        }
        ed.apply();
        Tarjeta.actualizar(c); // refresca la tarjeta y la esfera con lo recién leído
    }

    private static Notification.Builder aviso(Context c, String titulo, String texto) {
        PendingIntent abrir = PendingIntent.getActivity(c, 0, new Intent(c, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(c, CANAL)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(titulo)
            .setContentText(texto)
            .setContentIntent(abrir)
            .setAutoCancel(true);
    }

    private static Notification.Action boton(Context c, String texto, String que, int icono, String id, int numero) {
        Intent i = new Intent(c, Boton.class).setAction(que).putExtra("id", id).putExtra("aviso", numero);
        PendingIntent p = PendingIntent.getBroadcast(c, (que + id).hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Action.Builder(Icon.createWithResource(c, icono), texto, p).build();
    }

    /** Botones "Hecho" y "Mañana" de los avisos: guardan el cambio sin abrir la app. */
    public static class Boton extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            PendingResult resultado = goAsync();
            String id = i.getStringExtra("id"), que = i.getAction();
            int numero = i.getIntExtra("aviso", 0);
            new Thread(() -> {
                try {
                    Api api = new Api(c.getSharedPreferences("sesion", Context.MODE_PRIVATE));
                    if ("hecho".equals(que)) api.cambiarEstado(id, "hecho");
                    else api.mover(id, LocalDate.now().plusDays(1).toString());
                    c.getSystemService(NotificationManager.class).cancel(numero);
                    Tarjeta.actualizar(c);
                } catch (Exception ignorado) { }
                resultado.finish();
            }).start();
        }
    }
}
