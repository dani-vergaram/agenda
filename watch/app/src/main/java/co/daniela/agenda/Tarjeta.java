package co.daniela.agenda;

import android.content.ComponentName;
import android.content.Context;
import androidx.wear.protolayout.ActionBuilders;
import androidx.wear.protolayout.ColorBuilders;
import androidx.wear.protolayout.DimensionBuilders;
import androidx.wear.protolayout.LayoutElementBuilders;
import androidx.wear.protolayout.ModifiersBuilders;
import androidx.wear.protolayout.ResourceBuilders;
import androidx.wear.protolayout.TimelineBuilders;
import androidx.wear.tiles.RequestBuilders;
import androidx.wear.tiles.TileBuilders;
import androidx.wear.tiles.TileService;
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** Tarjeta del reloj (se ve deslizando desde la esfera): próxima reunión y pendientes, con lo último guardado. */
public class Tarjeta extends TileService {
    private static final String VERSION = "1";
    private static final int ROSA = 0xFFFCE1D9, TINTA = 0xFFF6ECE8, GRIS = 0xFFA7B4B8, MENTA = 0xFF9BE4D1, ROJO = 0xFFFF8A7F;

    /** Lo que muestran la tarjeta y la esfera. */
    static final class Resumen {
        boolean hayDatos;
        Api.Tarea proxima; // null si no hay reuniones por venir
        String dia = ""; // "" si es hoy; si no, "Mañana" o "18 nov"
        int pendientes, urgentes;

        /** "15:00", "Mañana 09:00" o "Hoy" si dura todo el día. */
        String cuando() {
            String s = (dia + " " + proxima.hora).trim();
            return s.isEmpty() ? "Hoy" : s;
        }
    }

    /** Arma el resumen con la copia guardada en el reloj; no usa internet. */
    static Resumen resumen(Context c) {
        Resumen r = new Resumen();
        List<Api.Tarea> lista = new Api(c.getSharedPreferences("sesion", Context.MODE_PRIVATE)).guardada();
        if (lista == null) return r;
        r.hayDatos = true;
        LocalDate fecha = LocalDate.now();
        LocalTime ahora = LocalTime.now();
        String hoy = fecha.toString();
        for (Api.Tarea t : lista) {
            if (t.fecha.isEmpty() || !"pendiente".equals(t.estado)) continue;
            if (!"evento".equals(t.tipo)) {
                if (t.fecha.compareTo(hoy) <= 0) {
                    r.pendientes++;
                    if ("alta".equals(t.prioridad)) r.urgentes++;
                }
                continue;
            }
            boolean porVenir = t.fecha.compareTo(hoy) > 0 || (t.fecha.equals(hoy) && !yaPaso(t, ahora));
            if (!porVenir) continue;
            if (r.proxima == null || (t.fecha + t.hora).compareTo(r.proxima.fecha + r.proxima.hora) < 0) r.proxima = t;
        }
        if (r.proxima != null && !r.proxima.fecha.equals(hoy)) {
            r.dia = r.proxima.fecha.equals(fecha.plusDays(1).toString()) ? "Mañana" : corta(r.proxima.fecha);
        }
        return r;
    }

    private static boolean yaPaso(Api.Tarea t, LocalTime ahora) {
        try {
            LocalTime inicio = LocalTime.parse(t.hora);
            LocalTime fin = !t.horaFin.isEmpty() ? LocalTime.parse(t.horaFin) : inicio.getHour() >= 23 ? LocalTime.MAX : inicio.plusHours(1);
            return !fin.isAfter(ahora);
        } catch (RuntimeException sinHora) {
            return false; // sin hora: dura todo el día
        }
    }

    private static String corta(String iso) {
        try {
            return LocalDate.parse(iso).format(DateTimeFormatter.ofPattern("d MMM", new Locale("es", "CO"))).replace(".", "");
        } catch (RuntimeException e) {
            return iso;
        }
    }

    /** Pide al reloj que vuelva a pintar la tarjeta y el dato de la esfera. */
    static void actualizar(Context c) {
        try {
            TileService.getUpdater(c).requestUpdate(Tarjeta.class);
            ComplicationDataSourceUpdateRequester.create(c, new ComponentName(c, Esfera.class)).requestUpdateAll();
        } catch (RuntimeException ignorado) { }
    }

    @Override protected ListenableFuture<TileBuilders.Tile> onTileRequest(RequestBuilders.TileRequest pedido) {
        return Futures.immediateFuture(new TileBuilders.Tile.Builder()
            .setResourcesVersion(VERSION)
            .setFreshnessIntervalMillis(15 * 60 * 1000L)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(contenido()))
            .build());
    }

    @Override protected ListenableFuture<ResourceBuilders.Resources> onTileResourcesRequest(RequestBuilders.ResourcesRequest pedido) {
        return Futures.immediateFuture(new ResourceBuilders.Resources.Builder().setVersion(VERSION).build());
    }

    private LayoutElementBuilders.LayoutElement contenido() {
        Resumen r = resumen(this);
        LayoutElementBuilders.Column.Builder col = new LayoutElementBuilders.Column.Builder()
            .setWidth(DimensionBuilders.expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER);
        if (!r.hayDatos) {
            col.addContent(texto("AGENDA", 11, GRIS, true, 1));
            col.addContent(texto("Abre la app para cargar tu agenda", 14, TINTA, false, 3));
        } else {
            if (r.proxima == null) {
                col.addContent(texto("AGENDA", 11, GRIS, true, 1));
                col.addContent(texto("Sin reuniones por venir", 14, TINTA, false, 2));
            } else {
                col.addContent(texto("PRÓXIMA REUNIÓN", 11, GRIS, true, 1));
                col.addContent(texto(r.cuando(), 24, ROSA, true, 1));
                col.addContent(texto(r.proxima.texto, 14, TINTA, false, 2));
            }
            col.addContent(new LayoutElementBuilders.Spacer.Builder().setHeight(DimensionBuilders.dp(6)).build());
            String pend = r.pendientes == 0 ? "Todo al día" : r.pendientes + (r.pendientes == 1 ? " pendiente" : " pendientes")
                + (r.urgentes == 0 ? "" : " · " + r.urgentes + (r.urgentes == 1 ? " urgente" : " urgentes"));
            col.addContent(texto(pend, 13, r.urgentes > 0 ? ROJO : MENTA, true, 1));
        }

        ModifiersBuilders.Clickable abrir = new ModifiersBuilders.Clickable.Builder()
            .setId("abrir")
            .setOnClick(new ActionBuilders.LaunchAction.Builder()
                .setAndroidActivity(new ActionBuilders.AndroidActivity.Builder()
                    .setPackageName(getPackageName())
                    .setClassName(MainActivity.class.getName())
                    .build())
                .build())
            .build();
        return new LayoutElementBuilders.Box.Builder()
            .setWidth(DimensionBuilders.expand())
            .setHeight(DimensionBuilders.expand())
            .setModifiers(new ModifiersBuilders.Modifiers.Builder()
                .setClickable(abrir)
                .setPadding(new ModifiersBuilders.Padding.Builder().setAll(DimensionBuilders.dp(26)).build())
                .build())
            .addContent(col.build())
            .build();
    }

    private static LayoutElementBuilders.Text texto(String s, float sp, int color, boolean negrita, int lineas) {
        return new LayoutElementBuilders.Text.Builder()
            .setText(s)
            .setMaxLines(lineas)
            .setOverflow(LayoutElementBuilders.TEXT_OVERFLOW_ELLIPSIZE_END)
            .setMultilineAlignment(LayoutElementBuilders.TEXT_ALIGN_CENTER)
            .setFontStyle(new LayoutElementBuilders.FontStyle.Builder()
                .setSize(DimensionBuilders.sp(sp))
                .setColor(ColorBuilders.argb(color))
                .setWeight(negrita ? LayoutElementBuilders.FONT_WEIGHT_BOLD : LayoutElementBuilders.FONT_WEIGHT_NORMAL)
                .build())
            .build();
    }
}
