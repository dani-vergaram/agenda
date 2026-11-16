package co.daniela.agenda;

import android.content.SharedPreferences;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Conexión con Firebase por su API web, sin librerías adicionales. Guarda una copia en el reloj para usarlo sin conexión. */
final class Api {
    static final class Tarea {
        String id, texto, fecha, estado, area, tipo, hora, horaFin, prioridad, municipio, nota;
        long creado;
    }

    /** Entidad de la agenda web (CAR, UAESP…) con su color "#rrggbb". */
    static final class Entidad { String nombre, color; }

    /** Respuesta de error del servidor (distinta de no tener conexión). */
    static final class Http extends IOException {
        final int codigo;
        Http(int codigo) { super("HTTP " + codigo); this.codigo = codigo; }
    }

    private interface Cambio { void en(Tarea t); }

    private static final Object COLA = new Object();

    private final SharedPreferences prefs;
    private String idToken;
    private long vence;

    Api(SharedPreferences prefs) { this.prefs = prefs; }

    boolean conSesion() { return prefs.contains("refresh") && prefs.contains("uid"); }

    void salir() { prefs.edit().clear().apply(); idToken = null; }

    void entrar(String correo, String clave) throws IOException, JSONException {
        JSONObject cuerpo = new JSONObject().put("email", correo).put("password", clave).put("returnSecureToken", true);
        JSONObject r = new JSONObject(pedir("POST",
            "https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=" + BuildConfig.API_KEY,
            cuerpo.toString(), "application/json", null));
        prefs.edit().putString("refresh", r.getString("refreshToken")).putString("uid", r.getString("localId")).apply();
        idToken = r.getString("idToken");
        vence = System.currentTimeMillis() + 50 * 60 * 1000;
    }

    private String token() throws IOException, JSONException {
        if (idToken != null && System.currentTimeMillis() < vence) return idToken;
        String form = "grant_type=refresh_token&refresh_token="
            + URLEncoder.encode(prefs.getString("refresh", ""), "UTF-8");
        JSONObject r = new JSONObject(pedir("POST",
            "https://securetoken.googleapis.com/v1/token?key=" + BuildConfig.API_KEY,
            form, "application/x-www-form-urlencoded", null));
        prefs.edit().putString("refresh", r.getString("refresh_token")).apply();
        idToken = r.getString("id_token");
        vence = System.currentTimeMillis() + 50 * 60 * 1000;
        return idToken;
    }

    private String raiz() { return "projects/" + BuildConfig.PROJECT_ID + "/databases/(default)/documents"; }

    private String usuario() { return raiz() + "/users/" + prefs.getString("uid", ""); }

    private String ruta(String id) { return usuario() + "/pendientes/" + id; }

    // ---------- Lectura ----------

    /** Envía lo que estuviera en cola, lee toda la agenda y la deja guardada en el reloj. */
    List<Tarea> listar() throws IOException, JSONException {
        sincronizar();
        List<Tarea> lista = new ArrayList<>();
        String pagina = "";
        do {
            String url = "https://firestore.googleapis.com/v1/" + usuario()
                + "/pendientes?pageSize=300" + (pagina.isEmpty() ? "" : "&pageToken=" + URLEncoder.encode(pagina, "UTF-8"));
            JSONObject r = new JSONObject(pedir("GET", url, null, null, token()));
            JSONArray docs = r.optJSONArray("documents");
            if (docs != null) {
                for (int i = 0; i < docs.length(); i++) {
                    JSONObject d = docs.getJSONObject(i);
                    JSONObject f = d.getJSONObject("fields");
                    Tarea t = new Tarea();
                    String nombre = d.getString("name");
                    t.id = nombre.substring(nombre.lastIndexOf('/') + 1);
                    t.texto = texto(f, "texto");
                    t.fecha = texto(f, "fecha");
                    t.estado = texto(f, "estado");
                    t.area = texto(f, "area");
                    t.tipo = texto(f, "tipo");
                    t.hora = texto(f, "hora");
                    t.horaFin = texto(f, "horaFin");
                    t.nota = texto(f, "nota");
                    t.prioridad = texto(f, "prioridad");
                    t.municipio = texto(f, "municipio");
                    if (t.municipio.isEmpty()) t.municipio = texto(f, "tema");
                    if (t.municipio.isEmpty()) t.municipio = texto(f, "sub");
                    JSONObject c = f.optJSONObject("creado");
                    if (c != null) t.creado = c.has("integerValue") ? Long.parseLong(c.getString("integerValue")) : (long) c.optDouble("doubleValue", 0);
                    lista.add(t);
                }
            }
            pagina = r.optString("nextPageToken", "");
        } while (!pagina.isEmpty());
        regrabar(lista);
        prefs.edit().putLong("cacheHora", System.currentTimeMillis()).apply();
        try { leerEntidades(); } catch (IOException sinEntidades) { }
        return lista;
    }

    private static String texto(JSONObject f, String k) {
        JSONObject v = f.optJSONObject(k);
        return v == null ? "" : v.optString("stringValue", "");
    }

    /** Lee las entidades y sus colores, tal como están configuradas en la agenda web. */
    private void leerEntidades() throws IOException, JSONException {
        JSONObject d = new JSONObject(pedir("GET", "https://firestore.googleapis.com/v1/" + usuario() + "/ajustes/entidades", null, null, token()));
        JSONObject campos = d.optJSONObject("fields");
        JSONObject lista = campos == null ? null : campos.optJSONObject("lista");
        JSONObject arreglo = lista == null ? null : lista.optJSONObject("arrayValue");
        JSONArray valores = arreglo == null ? null : arreglo.optJSONArray("values");
        JSONArray a = new JSONArray();
        if (valores != null) {
            for (int i = 0; i < valores.length(); i++) {
                JSONObject m = valores.getJSONObject(i).optJSONObject("mapValue");
                JSONObject f = m == null ? null : m.optJSONObject("fields");
                if (f != null) a.put(new JSONArray().put(texto(f, "nombre")).put(texto(f, "color")));
            }
        }
        prefs.edit().putString("entidades", a.toString()).apply();
    }

    /** Entidades guardadas en el reloj (vacío si todavía no se han leído). */
    List<Entidad> entidades() {
        List<Entidad> lista = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs.getString("entidades", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONArray f = a.getJSONArray(i);
                Entidad x = new Entidad();
                x.nombre = f.getString(0);
                x.color = f.getString(1);
                lista.add(x);
            }
        } catch (JSONException raro) { }
        return lista;
    }

    // ---------- Copia en el reloj ----------

    private void regrabar(List<Tarea> lista) {
        JSONArray a = new JSONArray();
        for (Tarea t : lista) {
            a.put(new JSONArray().put(t.id).put(t.texto).put(t.fecha).put(t.estado).put(t.area).put(t.tipo)
                .put(t.hora).put(t.horaFin).put(t.prioridad).put(t.municipio).put(t.nota).put(t.creado));
        }
        prefs.edit().putString("cache", a.toString()).apply();
    }

    /** Última lista guardada en el reloj, con los cambios hechos sin conexión; null si todavía no hay ninguna. */
    List<Tarea> guardada() {
        String s = prefs.getString("cache", null);
        if (s == null) return null;
        try {
            JSONArray a = new JSONArray(s);
            List<Tarea> lista = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) {
                JSONArray f = a.getJSONArray(i);
                Tarea t = new Tarea();
                t.id = f.getString(0);
                t.texto = f.getString(1);
                t.fecha = f.getString(2);
                t.estado = f.getString(3);
                t.area = f.getString(4);
                t.tipo = f.getString(5);
                t.hora = f.getString(6);
                t.horaFin = f.getString(7);
                t.prioridad = f.getString(8);
                t.municipio = f.getString(9);
                t.nota = f.getString(10);
                t.creado = f.getLong(11);
                lista.add(t);
            }
            return lista;
        } catch (JSONException e) {
            return null;
        }
    }

    /** Momento (en milisegundos) de la última lectura completa. */
    long horaGuardada() { return prefs.getLong("cacheHora", 0); }

    // ---------- Cambios ----------
    // Devuelven false cuando no había conexión: el cambio queda en el reloj y se envía solo después.

    boolean cambiarEstado(String id, String estado) throws IOException, JSONException {
        JSONObject cuando = "pendiente".equals(estado) ? new JSONObject().put("nullValue", JSONObject.NULL) : entero(System.currentTimeMillis());
        JSONObject campos = new JSONObject().put("estado", cadena(estado)).put("hechoEn", cuando);
        return enviar(actualizar(id, campos, "estado", "hechoEn"), id, t -> t.estado = estado);
    }

    boolean mover(String id, String fecha) throws IOException, JSONException {
        return enviar(actualizar(id, new JSONObject().put("fecha", cadena(fecha)), "fecha"), id, t -> t.fecha = fecha);
    }

    boolean crear(String texto, String fecha, String area) throws IOException, JSONException {
        long ahora = System.currentTimeMillis();
        Tarea t = new Tarea();
        t.id = "r" + Long.toString(ahora, 36);
        t.texto = texto; t.fecha = fecha; t.estado = "pendiente"; t.area = area; t.creado = ahora;
        t.tipo = ""; t.hora = ""; t.horaFin = ""; t.prioridad = ""; t.municipio = ""; t.nota = "";
        JSONObject campos = new JSONObject()
            .put("texto", cadena(texto)).put("fecha", cadena(fecha)).put("estado", cadena("pendiente")).put("nota", cadena(""))
            .put("creado", entero(ahora));
        if (!area.isEmpty()) campos.put("area", cadena(area));
        boolean enviado = enviar(new JSONObject().put("update", new JSONObject().put("name", ruta(t.id)).put("fields", campos)), null, null);
        List<Tarea> lista = guardada();
        if (lista != null) { lista.add(t); regrabar(lista); }
        return enviado;
    }

    private static JSONObject cadena(String s) throws JSONException { return new JSONObject().put("stringValue", s); }

    private static JSONObject entero(long n) throws JSONException { return new JSONObject().put("integerValue", Long.toString(n)); }

    private JSONObject actualizar(String id, JSONObject campos, String... mascara) throws JSONException {
        JSONArray m = new JSONArray();
        for (String s : mascara) m.put(s);
        return new JSONObject()
            .put("update", new JSONObject().put("name", ruta(id)).put("fields", campos))
            .put("updateMask", new JSONObject().put("fieldPaths", m))
            .put("currentDocument", new JSONObject().put("exists", true));
    }

    /** Envía el cambio o, si no hay conexión, lo deja en cola. También lo aplica a la copia del reloj. */
    private boolean enviar(JSONObject escritura, String id, Cambio local) throws IOException, JSONException {
        boolean enviado = true;
        try {
            sincronizar();
            commit(escritura, token());
        } catch (Http e) {
            throw e;
        } catch (IOException sinConexion) {
            synchronized (COLA) {
                JSONArray c = new JSONArray(prefs.getString("cola", "[]"));
                c.put(escritura);
                prefs.edit().putString("cola", c.toString()).commit();
            }
            enviado = false;
        }
        List<Tarea> lista = id == null ? null : guardada();
        if (lista != null) {
            for (Tarea t : lista) if (t.id.equals(id)) local.en(t);
            regrabar(lista);
        }
        return enviado;
    }

    /** Cuántos cambios hechos sin conexión faltan por enviar. */
    int porEnviar() {
        try { return new JSONArray(prefs.getString("cola", "[]")).length(); } catch (JSONException e) { return 0; }
    }

    /** Envía en orden lo que quedó en cola. Si se cae la conexión, lo que falte sigue en cola. */
    void sincronizar() throws IOException, JSONException {
        synchronized (COLA) {
            JSONArray c = new JSONArray(prefs.getString("cola", "[]"));
            if (c.length() == 0) return;
            String t = token();
            while (c.length() > 0) {
                try {
                    commit(c.getJSONObject(0), t);
                } catch (Http e) {
                    // 400, 404 o 409: ese elemento ya no existe en la agenda; el cambio se descarta. Lo demás se reintenta luego.
                    if (e.codigo != 400 && e.codigo != 404 && e.codigo != 409) throw e;
                }
                c.remove(0);
                prefs.edit().putString("cola", c.toString()).commit();
            }
        }
    }

    private void commit(JSONObject escritura, String bearer) throws IOException, JSONException {
        String cuerpo = new JSONObject().put("writes", new JSONArray().put(escritura)).toString();
        pedir("POST", "https://firestore.googleapis.com/v1/" + raiz() + ":commit", cuerpo, "application/json", bearer);
    }

    private static String pedir(String metodo, String url, String cuerpo, String tipo, String bearer) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(metodo);
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        if (bearer != null) c.setRequestProperty("Authorization", "Bearer " + bearer);
        if (cuerpo != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", tipo);
            try (OutputStream o = c.getOutputStream()) { o.write(cuerpo.getBytes(StandardCharsets.UTF_8)); }
        }
        int estado = c.getResponseCode();
        InputStream in = estado < 400 ? c.getInputStream() : c.getErrorStream();
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        if (in != null) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            in.close();
        }
        c.disconnect();
        if (estado >= 400) throw new Http(estado);
        return b.toString("UTF-8");
    }
}
