package co.daniela.agenda;

import android.app.PendingIntent;
import android.content.Intent;
import androidx.wear.watchface.complications.data.ComplicationData;
import androidx.wear.watchface.complications.data.ComplicationType;
import androidx.wear.watchface.complications.data.LongTextComplicationData;
import androidx.wear.watchface.complications.data.PlainComplicationText;
import androidx.wear.watchface.complications.data.ShortTextComplicationData;
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService;
import androidx.wear.watchface.complications.datasource.ComplicationRequest;

/** Dato para la esfera del reloj: hora de la próxima reunión o, si no hay, cuántos pendientes quedan. */
public class Esfera extends ComplicationDataSourceService {
    @Override public void onComplicationRequest(ComplicationRequest pedido, ComplicationRequestListener oyente) {
        Tarjeta.Resumen r = Tarjeta.resumen(this);
        boolean conHora = r.proxima != null && !r.proxima.hora.isEmpty();
        String corto = conHora ? r.proxima.hora : String.valueOf(r.pendientes);
        String titulo = !conHora ? "pend." : r.dia.isEmpty() ? "reunión" : r.dia;
        String largo = r.proxima != null ? r.cuando() + " · " + r.proxima.texto
            : r.pendientes + (r.pendientes == 1 ? " pendiente" : " pendientes");
        try {
            oyente.onComplicationData(dato(pedido.getComplicationType(), corto, titulo, largo));
        } catch (Exception ignorado) { }
    }

    @Override public ComplicationData getPreviewData(ComplicationType tipo) {
        return dato(tipo, "15:00", "reunión", "15:00 · Mesa técnica");
    }

    private ComplicationData dato(ComplicationType tipo, String corto, String titulo, String largo) {
        PendingIntent abrir = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        if (tipo == ComplicationType.LONG_TEXT) {
            return new LongTextComplicationData.Builder(plano(largo), plano(largo)).setTapAction(abrir).build();
        }
        if (tipo != ComplicationType.SHORT_TEXT) return null;
        return new ShortTextComplicationData.Builder(plano(corto), plano(largo)).setTitle(plano(titulo)).setTapAction(abrir).build();
    }

    private static PlainComplicationText plano(String s) {
        return new PlainComplicationText.Builder(s).build();
    }
}
