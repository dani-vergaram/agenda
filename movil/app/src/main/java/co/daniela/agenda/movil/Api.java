package co.daniela.agenda.movil;

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

/** Conexión con Firebase por su API web, sin librerías adicionales. */
final class Api {
    static final class Tarea {
        String id, texto, fecha, estado, area, tipo, hora, prioridad, municipio;
        long creado;
    }

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

    private String ruta(String id) { return raiz() + "/users/" + prefs.getString("uid", "") + "/pendientes/" + id; }

    List<Tarea> listar() throws IOException, JSONException { return leer(descargar()); }

    /** Descarga todos los documentos de pendientes, con un reintento si falla la red. */
    JSONArray descargar() throws IOException, JSONException {
        try { return descargarUnaVez(); }
        catch (IOException e) {
            try { Thread.sleep(2500); } catch (InterruptedException ignorado) { }
            return descargarUnaVez();
        }
    }

    private JSONArray descargarUnaVez() throws IOException, JSONException {
        JSONArray todos = new JSONArray();
        String pagina = "";
        do {
            String url = "https://firestore.googleapis.com/v1/" + raiz() + "/users/" + prefs.getString("uid", "")
                + "/pendientes?pageSize=300" + (pagina.isEmpty() ? "" : "&pageToken=" + URLEncoder.encode(pagina, "UTF-8"));
            JSONObject r = new JSONObject(pedir("GET", url, null, null, token()));
            JSONArray docs = r.optJSONArray("documents");
            if (docs != null) for (int i = 0; i < docs.length(); i++) todos.put(docs.get(i));
            pagina = r.optString("nextPageToken", "");
        } while (!pagina.isEmpty());
        return todos;
    }

    static List<Tarea> leer(JSONArray docs) {
        List<Tarea> lista = new ArrayList<>();
        for (int i = 0; i < docs.length(); i++) {
            try {
                JSONObject d = docs.getJSONObject(i);
                JSONObject f = d.optJSONObject("fields");
                if (f == null) continue;
                Tarea t = new Tarea();
                String nombre = d.optString("name", "");
                t.id = nombre.substring(nombre.lastIndexOf('/') + 1);
                t.texto = texto(f, "texto");
                t.fecha = texto(f, "fecha");
                t.estado = texto(f, "estado");
                t.area = texto(f, "area");
                t.tipo = texto(f, "tipo");
                t.hora = texto(f, "hora");
                t.prioridad = texto(f, "prioridad");
                t.municipio = texto(f, "municipio");
                if (t.municipio.isEmpty()) t.municipio = texto(f, "tema");
                if (t.municipio.isEmpty()) t.municipio = texto(f, "sub");
                JSONObject c = f.optJSONObject("creado");
                if (c != null) t.creado = c.has("integerValue") ? Long.parseLong(c.getString("integerValue")) : (long) c.optDouble("doubleValue", 0);
                if (!t.hora.matches("\\d{1,2}:\\d{2}")) t.hora = "";
                if (t.hora.length() == 4) t.hora = "0" + t.hora;
                if (!t.fecha.matches("\\d{4}-\\d{2}-\\d{2}")) t.fecha = "";
                lista.add(t);
            } catch (Exception ignorado) { }
        }
        return lista;
    }

    private static String texto(JSONObject f, String k) {
        JSONObject v = f.optJSONObject(k);
        return v == null ? "" : v.optString("stringValue", "");
    }

    private static JSONObject cadena(String s) throws JSONException { return new JSONObject().put("stringValue", s); }

    void cambiarEstado(String id, String estado) throws IOException, JSONException {
        JSONObject w = new JSONObject()
            .put("update", new JSONObject().put("name", ruta(id)).put("fields", new JSONObject().put("estado", cadena(estado))))
            .put("updateMask", new JSONObject().put("fieldPaths", new JSONArray().put("estado")))
            .put("currentDocument", new JSONObject().put("exists", true));
        commit(w);
    }

    void crear(String texto, String fecha) throws IOException, JSONException {
        long ahora = System.currentTimeMillis();
        String id = "r" + Long.toString(ahora, 36);
        JSONObject campos = new JSONObject()
            .put("texto", cadena(texto)).put("fecha", cadena(fecha)).put("estado", cadena("pendiente")).put("nota", cadena(""))
            .put("creado", new JSONObject().put("integerValue", Long.toString(ahora)));
        commit(new JSONObject().put("update", new JSONObject().put("name", ruta(id)).put("fields", campos)));
    }

    private void commit(JSONObject escritura) throws IOException, JSONException {
        String cuerpo = new JSONObject().put("writes", new JSONArray().put(escritura)).toString();
        pedir("POST", "https://firestore.googleapis.com/v1/" + raiz() + ":commit", cuerpo, "application/json", token());
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
        if (estado >= 400) throw new IOException("HTTP " + estado + " " + b.toString("UTF-8"));
        return b.toString("UTF-8");
    }
}
