/* Agenda de Daniela — filtros (urgencia, tema, texto) y tablero de control.
   Se carga después del script principal de index.html:
   <script src="extras.js"></script>  justo antes de </body>. */
(() => {
  if (typeof render !== "function" || typeof pintarContenido !== "function" || typeof S === "undefined") return;

  /* ---------- Estado de los filtros (se recuerda en este navegador) ---------- */
  const F = { prio: "", tema: "", q: "" };
  try { Object.assign(F, JSON.parse(localStorage.getItem("agenda-filtros") || "{}")); } catch (e) {}
  const guardarF = () => { try { localStorage.setItem("agenda-filtros", JSON.stringify(F)); } catch (e) {} };
  const norm = s => (s || "").normalize("NFD").replace(/[\u0300-\u036f]/g, "").toLowerCase();
  const activo = () => !!(F.prio || F.tema || F.q.trim() || F.diego);
  const conDiego = t => !!(t.diego && !t.diego.hablado);
  const pasa = t => {
    if (F.prio === "sin" ? !!t.prioridad : (F.prio && t.prioridad !== F.prio)) return false;
    if (F.tema && (subDe(t) || "") !== F.tema) return false;
    if (F.diego && !conDiego(t)) return false;
    const q = norm(F.q).trim();
    if (q) {
      const hay = norm([t.texto, t.nota, subDe(t), t.area, t.correoAsunto, t.correoDe, t.diego && t.diego.pregunta, t.diego && t.diego.linea, ...(t.apuntes || []).map(a => a.texto)].join(" "));
      if (!q.split(/\s+/).every(w => hay.includes(w))) return false;
    }
    return true;
  };

  /* ---------- Estilos ---------- */
  const css = document.createElement("style");
  css.textContent = "\n  .filtros { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; margin: 10px 0 4px; }\n  .filtros input[type=search] { flex: 1 1 220px; min-width: 0; min-height: 38px; padding: 0 12px; border: 1px solid var(--line); border-radius: 10px; background: var(--paper); color: var(--ink); font: inherit; }\n  .filtros select { min-height: 38px; padding: 0 10px; border: 1px solid var(--line); border-radius: 10px; background: var(--paper); color: var(--ink); font: inherit; max-width: 100%; }\n  .filtros .cuenta { width: 100%; margin: 0; color: var(--muted); font-size: .8125rem; }\n  .filtros .cuenta[hidden] { display: none; }\n  .tb { display: grid; gap: 18px; margin-top: 8px; }\n  .tb h3 { margin: 0 0 8px; font-size: .9375rem; }\n  .tb-kpis { display: grid; grid-template-columns: repeat(auto-fit, minmax(130px, 1fr)); gap: 10px; }\n  .tb-kpi { padding: 12px 14px; border: 1px solid var(--line); border-radius: 14px; background: var(--paper); }\n  .tb-kpi b { display: block; font-size: 1.75rem; line-height: 1.1; font-variant-numeric: tabular-nums; color: var(--ink); }\n  .tb-kpi span { color: var(--muted); font-size: .8125rem; }\n  .tb-kpi.r b { color: var(--sem-r); } .tb-kpi.a b { color: var(--sem-a); } .tb-kpi.v b { color: var(--sem-v); }\n  .tb-card { padding: 14px; border: 1px solid var(--line); border-radius: 14px; background: var(--paper); overflow-x: auto; }\n  .tb-tabla { width: 100%; border-collapse: collapse; font-size: .875rem; font-variant-numeric: tabular-nums; }\n  .tb-tabla th, .tb-tabla td { padding: 7px 6px; border-bottom: 1px solid var(--line); text-align: right; white-space: nowrap; }\n  .tb-tabla th:first-child, .tb-tabla td:first-child { text-align: left; }\n  .tb-tabla th { color: var(--muted); font-weight: 600; font-size: .75rem; }\n  .tb-tabla td.barra { width: 40%; min-width: 120px; }\n  .tb-bar { display: flex; height: 12px; border-radius: 99px; overflow: hidden; background: var(--line); }\n  .tb-bar i { display: block; height: 100%; }\n  .tb-bar .h { background: var(--sem-v); } .tb-bar .p { background: var(--accent); } .tb-bar .x { background: var(--muted); opacity: .45; }\n  .tb-ley { display: flex; flex-wrap: wrap; gap: 14px; margin-top: 8px; color: var(--muted); font-size: .75rem; }\n  .tb-ley i { display: inline-block; width: 10px; height: 10px; border-radius: 3px; margin-right: 5px; vertical-align: -1px; }\n  .tb-tema { border: 0; background: none; padding: 0; color: var(--ink); font: inherit; text-align: left; text-decoration: underline; text-decoration-color: var(--line); cursor: pointer; }\n  .tb-tema:hover { text-decoration-color: var(--accent); }\n  .tb-nota { color: var(--muted); font-size: .75rem; margin: 6px 0 0; }";
  document.head.append(css);

  /* ---------- Barra de filtros ---------- */
  const el = (tag, props = {}, ...hijos) => { const e = document.createElement(tag); Object.assign(e, props); e.append(...hijos); return e; };
  const buscar = el("input", { type: "search", placeholder: "Buscar en pendientes, notas, correos o remitentes", value: F.q });
  buscar.setAttribute("aria-label", "Buscar por texto");
  const selPrio = el("select");
  selPrio.setAttribute("aria-label", "Filtrar por urgencia");
  [["", "Todas las urgencias"], ["alta", "🔴 Urgente"], ["media", "🟡 Importante"], ["baja", "🟢 Puede esperar"], ["sin", "Sin prioridad"]]
    .forEach(([v, t]) => selPrio.append(el("option", { value: v, textContent: t })));
  selPrio.value = F.prio;
  const selTema = el("select");
  selTema.setAttribute("aria-label", "Filtrar por tema o municipio");
  const limpiar = el("button", { type: "button", className: "mini", textContent: "Limpiar filtros" });
  const cuenta = el("p", { className: "cuenta" });
  cuenta.setAttribute("aria-live", "polite");
  const chipDiego = el("button", { type: "button", className: "chip" });
  chipDiego.onclick = () => { F.diego = !F.diego; guardarF(); if (F.diego && S.vista !== "todos" && S.vista !== "tablero") S.vista = "todos"; render(); };
  const nuevoDiego = el("button", { type: "button", className: "mini", textContent: "+ Tema para Diego" });
  nuevoDiego.onclick = () => window.__accMenu && window.__accMenu.nuevoDiego();
  const barra = el("div", { className: "filtros" }, buscar, selPrio, selTema, chipDiego, limpiar, cuenta);
  barra.setAttribute("role", "search");
  (document.getElementById("subs") || document.getElementById("form")).after(barra);

  let espera = null;
  buscar.addEventListener("input", () => { clearTimeout(espera); espera = setTimeout(() => { F.q = buscar.value; guardarF(); render(); }, 200); });
  selPrio.onchange = () => { F.prio = selPrio.value; guardarF(); render(); };
  selTema.onchange = () => { F.tema = selTema.value; guardarF(); render(); };
  limpiar.onclick = () => { F.prio = ""; F.tema = ""; F.q = ""; F.diego = false; buscar.value = ""; selPrio.value = ""; guardarF(); render(); };

  function pintarTemas() {
    const grupos = (typeof ENT !== "undefined" ? ENT : []).filter(e => !S.area || e.nombre === S.area)
      .map(e => [e, opcionesDe(e).slice().sort((a, b) => a.localeCompare(b, "es"))]).filter(([, o]) => o.length);
    const actual = F.tema;
    selTema.replaceChildren(el("option", { value: "", textContent: "Todos los temas y municipios" }));
    grupos.forEach(([e, ops]) => {
      const g = el("optgroup"); g.label = e.nombre + (e.etiqueta ? " · " + e.etiqueta : "");
      ops.forEach(o => g.append(el("option", { value: o, textContent: o })));
      selTema.append(g);
    });
    if (actual && ![...selTema.options].some(o => o.value === actual)) selTema.append(el("option", { value: actual, textContent: actual }));
    selTema.value = actual;
  }

  /* ---------- Pestaña Tablero ---------- */
  const tab = el("button", { className: "tab", type: "button", id: "tabTablero", textContent: "Tablero" });
  tab.setAttribute("role", "tab");
  document.getElementById("tabHechos").after(tab);
  tab.onclick = () => { S.vista = "tablero"; render(); window.scrollTo({ top: 0, behavior: "smooth" }); };

  const mesDe = t => (t.correoFecha || t.fecha || (t.creado ? iso(new Date(t.creado)) : "")).slice(0, 7);
  const nombreMes = m => { const [y, mm] = m.split("-").map(Number); return mayus(fmtMes.format(new Date(y, mm - 1, 1)).replace(" de ", " ")); };
  const barraDe = (hh, pp, xx, max) => {
    const b = el("div", { className: "tb-bar" });
    [["h", hh], ["p", pp], ["x", xx]].forEach(([c, n]) => { if (n) { const i = el("i", { className: c }); i.style.width = (100 * n / max) + "%"; b.append(i); } });
    return b;
  };

  function tablero() {
    const c = document.getElementById("contenido"); c.replaceChildren();
    const hoy = hoyIso(), mesHoy = hoy.slice(0, 7);
    const items = todos().map(([, t]) => t).filter(t => !esEv(t));
    const abiertos = items.filter(t => t.estado === "pendiente");
    const hechos = items.filter(t => t.estado === "hecho");
    const hechosMes = hechos.filter(t => t.hechoEn && iso(new Date(t.hechoEn)).slice(0, 7) === mesHoy).length;
    const kpi = (n, txt, cl) => el("div", { className: "tb-kpi " + (cl || "") }, el("b", { textContent: n }), el("span", { textContent: txt }));
    c.append(el("div", { className: "tb" },
      el("div", { className: "tb-kpis" },
        kpi(abiertos.length, "Pendientes abiertos"),
        kpi(abiertos.filter(t => t.prioridad === "alta").length, "Urgentes abiertos", "r"),
        kpi(abiertos.filter(t => t.prioridad === "media").length, "Importantes abiertos", "a"),
        kpi(abiertos.filter(t => t.fecha && t.fecha < hoy).length, "Atrasados"),
        kpi(hechos.length, "Atendidos en total", "v"),
        kpi(hechosMes, "Atendidos este mes", "v"),
        kpi(abiertos.filter(conDiego).length, "Para hablar con Diego"))));

    // Por mes de origen (fecha del correo, o la fecha del pendiente)
    const meses = {};
    items.forEach(t => { const m = mesDe(t) || "sin"; (meses[m] = meses[m] || { h: 0, p: 0, x: 0 }); meses[m][t.estado === "hecho" ? "h" : t.estado === "tachado" ? "x" : "p"]++; });
    const porFecha = (a, b) => (a === "sin") - (b === "sin") || b.localeCompare(a);
    const claves = Object.keys(meses).sort(porFecha);
    const max = Math.max(1, ...claves.map(k => meses[k].h + meses[k].p + meses[k].x));
    const tabla = el("table", { className: "tb-tabla" },
      el("thead", {}, el("tr", {}, ...["Mes", "Total", "Atendidos", "Abiertos", "Tachados", "% atendido", ""].map(x => el("th", { textContent: x })))),
      el("tbody", {}, ...claves.map(k => {
        const m = meses[k], tot = m.h + m.p + m.x, pct = tot ? Math.round(100 * m.h / (m.h + m.p || 1)) : 0;
        return el("tr", {},
          el("td", { textContent: k === "sin" ? "Sin fecha" : nombreMes(k) }),
          el("td", { textContent: tot }), el("td", { textContent: m.h }), el("td", { textContent: m.p }), el("td", { textContent: m.x }),
          el("td", { textContent: (m.h + m.p) ? pct + " %" : "—" }),
          el("td", { className: "barra" }, barraDe(m.h, m.p, m.x, max)));
      })));
    const ley = el("div", { className: "tb-ley" },
      el("span", {}, Object.assign(el("i"), { style: "background:var(--sem-v)" }), "Atendidos"),
      el("span", {}, Object.assign(el("i"), { style: "background:var(--accent)" }), "Abiertos"),
      el("span", {}, Object.assign(el("i"), { style: "background:var(--muted);opacity:.45" }), "Tachados"));
    c.firstChild.append(el("section", { className: "tb-card" }, el("h3", { textContent: "Pendientes por mes" }), tabla, ley,
      el("p", { className: "tb-nota", textContent: "El mes es el del correo que originó el pendiente; si no viene de un correo, el de su fecha." })));

    // Atendidos por mes en que se chulearon
    const porCierre = {};
    hechos.forEach(t => { const m = t.hechoEn ? iso(new Date(t.hechoEn)).slice(0, 7) : "sin"; porCierre[m] = (porCierre[m] || 0) + 1; });
    const kc = Object.keys(porCierre).sort(porFecha), maxc = Math.max(1, ...kc.map(k => porCierre[k]));
    if (kc.length) c.firstChild.append(el("section", { className: "tb-card" }, el("h3", { textContent: "Atendidos según el mes en que los chuleaste" }),
      el("table", { className: "tb-tabla" }, el("tbody", {}, ...kc.map(k => el("tr", {},
        el("td", { textContent: k === "sin" ? "Sin registro de fecha" : nombreMes(k) }), el("td", { textContent: porCierre[k] }),
        el("td", { className: "barra" }, barraDe(porCierre[k], 0, 0, maxc))))))));

    // Abiertos por tema o municipio
    const porTema = {};
    abiertos.forEach(t => { const k = (t.area ? t.area + " · " : "") + (subDe(t) || "Sin tema"); const o = (porTema[k] = porTema[k] || { n: 0, a: 0, m: 0, sub: subDe(t) || "" }); o.n++; if (t.prioridad === "alta") o.a++; if (t.prioridad === "media") o.m++; });
    const kt = Object.keys(porTema).sort((a, b) => porTema[b].n - porTema[a].n || a.localeCompare(b, "es"));
    if (kt.length) c.firstChild.append(el("section", { className: "tb-card" }, el("h3", { textContent: "Abiertos por tema o municipio" }),
      el("table", { className: "tb-tabla" },
        el("thead", {}, el("tr", {}, ...["Tema", "Abiertos", "🔴 Urgentes", "🟡 Importantes"].map(x => el("th", { textContent: x })))),
        el("tbody", {}, ...kt.map(k => {
          const o = porTema[k];
          const b = el("button", { className: "tb-tema", type: "button", textContent: k, title: "Ver estos pendientes" });
          b.onclick = () => { F.tema = o.sub; guardarF(); S.vista = "todos"; render(); };
          return el("tr", {}, el("td", {}, b), el("td", { textContent: o.n }), el("td", { textContent: o.a }), el("td", { textContent: o.m }));
        })))));
    if (!items.length) c.append(el("div", { className: "vacio" }, el("strong", { textContent: "No hay pendientes con estos filtros" })));
  }

  /* ---------- Enganche con la app ---------- */
  const pcOriginal = pintarContenido;
  window.pintarContenido = function () { if (S.vista === "tablero" && !S.cargando) return tablero(); return pcOriginal.apply(this, arguments); };

  const renderOriginal = render;
  window.render = function () {
    pintarTemas();
    const total = [...S.items.values()].length;
    if (activo()) {
      const todosItems = S.items;
      S.items = new Map([...todosItems].filter(([, t]) => pasa(t)));
      const n = S.items.size;
      try { renderOriginal.apply(this, arguments); } finally { S.items = todosItems; }
      cuenta.hidden = false; cuenta.textContent = "Filtro activo: " + n + " de " + total + " elementos coinciden.";
    } else {
      renderOriginal.apply(this, arguments);
      cuenta.hidden = true;
    }
    limpiar.hidden = !activo();
    const nD = [...S.items.values()].filter(t => t.estado !== "tachado" && conDiego(t)).length;
    chipDiego.textContent = "🗣️ Hablar con Diego (" + nD + ")";
    chipDiego.setAttribute("aria-pressed", !!F.diego);
    if (window.__decorar) window.__decorar();
    tab.setAttribute("aria-selected", S.vista === "tablero");
  };
  render();
})();

/* ---------- Mantener presionado un pendiente: menú de acciones ---------- */
(() => {
  if (typeof S === "undefined" || typeof cambiar !== "function" || typeof quitarItem !== "function") return;
  const contenido = document.getElementById("contenido");
  if (!contenido) return;

  const css = document.createElement("style");
  css.textContent = "\n  #contenido li.task { -webkit-touch-callout: none; }\n  #contenido li.task.presionando { transform: scale(.985); transition: transform .35s ease; background: var(--soft); border-radius: 12px; }\n  dialog.acciones { width: min(440px, calc(100vw - 24px)); max-height: calc(100dvh - 24px); margin: auto auto 12px; padding: 0; border: 1px solid var(--line); border-radius: 18px; background: var(--paper); color: var(--ink); box-shadow: 0 12px 40px rgba(0,0,0,.25); overflow: auto; }\n  @media (min-width: 700px) { dialog.acciones { margin: auto; } }\n  dialog.acciones::backdrop { background: rgba(20, 28, 31, .45); }\n  .acc-cab { padding: 16px 18px 10px; border-bottom: 1px solid var(--line); }\n  .acc-cab b { display: block; font-size: 1rem; line-height: 1.3; }\n  .acc-cab small { display: block; margin-top: 4px; color: var(--muted); font-size: .75rem; line-height: 1.35; }\n  .acc-lista { display: grid; padding: 6px; }\n  .acc-lista button { display: flex; align-items: center; gap: 12px; min-height: 48px; padding: 0 14px; border: 0; border-radius: 12px; background: none; color: var(--ink); font: inherit; font-weight: 600; text-align: left; cursor: pointer; }\n  .acc-lista button:hover, .acc-lista button:focus-visible { background: var(--soft); outline: none; }\n  .acc-lista button .ic { width: 22px; text-align: center; font-size: 1.05rem; }\n  .acc-lista button.peligro { color: var(--sem-r); }\n  .acc-form { display: grid; gap: 10px; padding: 14px 18px 18px; }\n  .acc-form label { display: grid; gap: 4px; color: var(--muted); font-size: .8125rem; font-weight: 600; }\n  .acc-form input, .acc-form select, .acc-form textarea { min-height: 40px; padding: 8px 10px; border: 1px solid var(--line); border-radius: 10px; background: var(--bg); color: var(--ink); font: inherit; font-weight: 400; }\n  .acc-form textarea { min-height: 70px; resize: vertical; }\n  .acc-fila { display: flex; flex-wrap: wrap; gap: 8px; }\n  .acc-fila > * { flex: 1 1 140px; }\n  .acc-rapidas { display: flex; flex-wrap: wrap; gap: 8px; }\n  .acc-pie { display: flex; justify-content: flex-end; gap: 8px; padding-top: 4px; }\n  .acc-aviso { margin: 0; color: var(--ink); font-size: .9375rem; line-height: 1.4; }\n  .marca-extra { min-height: 24px; padding: 0 9px; border: 1px solid var(--accent); border-radius: 99px; background: var(--soft); color: var(--ink); font: inherit; font-size: .6875rem; font-weight: 700; cursor: pointer; }\n  .marca-extra.hecho { border-color: var(--sem-v); }\n  .marca-extra.suave { border-style: dashed; background: none; color: var(--muted); font-weight: 600; }\n  .coment-diego { display: block; width: 100%; margin-top: 6px; padding: 7px 10px; border: 0; border-left: 3px solid var(--accent); border-radius: 0 10px 10px 0; background: var(--soft); color: var(--ink); font: inherit; font-size: .8125rem; line-height: 1.35; text-align: left; white-space: pre-wrap; cursor: pointer; }\n  .coment-diego.hecho { border-left-color: var(--sem-v); }\n  .coment-diego b { font-weight: 700; }\n  .acc-tit { margin: 0; font-size: .9375rem; }\n  .acc-check { display: flex !important; align-items: center; gap: 8px; color: var(--ink) !important; }\n  .acc-check input { min-height: 0 !important; width: 18px; height: 18px; }\n  .apuntes { list-style: none; margin: 4px 0 0; padding: 0; display: grid; gap: 8px; }\n  .apunte { display: flex; gap: 8px; align-items: flex-start; justify-content: space-between; padding: 10px 12px; border: 1px solid var(--line); border-radius: 12px; background: var(--bg); }\n  .apunte small { color: var(--muted); font-size: .6875rem; }\n  .apunte p { margin: 2px 0 0; white-space: pre-wrap; font-size: .875rem; }\n  .acc-vacio { margin: 0; color: var(--muted); font-size: .8125rem; }";
  document.head.append(css);

  const el = (tag, props = {}, ...hijos) => { const e = document.createElement(tag); Object.assign(e, props); e.append(...hijos.filter(x => x != null)); return e; };
  const dlg = el("dialog", { className: "acciones" });
  dlg.setAttribute("aria-label", "Acciones del pendiente");
  document.body.append(dlg);
  dlg.addEventListener("click", e => { if (e.target === dlg) dlg.close(); });

  const fmtF = s => s ? s.split("-").reverse().join("/") : "sin fecha";
  const idDe = li => { const k = li && li.querySelector(".check"); return k && k.dataset.key ? k.dataset.key.slice(1) : null; };
  const proxLunes = () => { const d = new Date(); d.setDate(d.getDate() + ((8 - d.getDay()) % 7 || 7)); return iso(d); };
  const cerrar = () => { if (dlg.open) dlg.close(); };

  function cabecera(t) {
    const partes = [t.area, subDe(t), t.fecha ? fmtF(t.fecha) : null, t.prioridad ? PRIO[t.prioridad] : null].filter(Boolean).join(" · ");
    return el("div", { className: "acc-cab" }, el("b", { textContent: t.texto }), partes ? el("small", { textContent: partes }) : null);
  }
  const btn = (ic, txt, fn, cl) => { const b = el("button", { type: "button", className: cl || "" }, el("span", { className: "ic", textContent: ic }), txt); b.onclick = fn; return b; };

  function menu(id) {
    const t = S.items.get(id); if (!t) return;
    const ev = esEv(t), hecho = t.estado === "hecho";
    dlg.replaceChildren(cabecera(t), el("div", { className: "acc-lista" },
      btn("✏️", "Editar", () => editar(id)),
      btn("📅", "Mover de fecha", () => mover(id)),
      !ev ? btn("🚦", "Cambiar prioridad", () => prioridad(id)) : null,
      btn("📝", "Mis apuntes" + ((t.apuntes || []).length ? " (" + t.apuntes.length + ")" : ""), () => apuntes(id)),
      btn("🗣️", t.diego ? (t.diego.hablado ? "Hablado con Diego: ver línea" : "Hablarlo con Diego (marcado)") : "Hablarlo con Diego", () => diego(id)),
      btn(hecho ? "↩️" : "✅", hecho ? "Volver a pendiente" : (ev ? "Marcar como realizada" : "Marcar como atendido"), () => {
        cambiar(id, { estado: hecho ? "pendiente" : "hecho", hechoEn: hecho ? null : Date.now() }); cerrar(); }),
      btn("🗑️", "Eliminar", () => eliminar(id), "peligro"),
      btn("✕", "Cancelar", cerrar)));
    if (!dlg.open) dlg.showModal();
    const primero = dlg.querySelector(".acc-lista button"); if (primero) primero.focus();
  }

  function editar(id) {
    const t = S.items.get(id); if (!t) return;
    const e = ent(t.area), ev = esEv(t);
    const fTexto = el("textarea", { value: t.texto, rows: 2 });
    const fNota = el("textarea", { value: t.nota || "", rows: 3 });
    const fFecha = el("input", { type: "date", value: t.fecha || "" });
    const fPrio = el("select");
    [["", "Sin prioridad"], ["alta", "🔴 Urgente"], ["media", "🟡 Importante"], ["baja", "🟢 Puede esperar"]].forEach(([v, x]) => fPrio.append(el("option", { value: v, textContent: x })));
    fPrio.value = t.prioridad || "";
    const fArea = el("select");
    fArea.append(el("option", { value: "", textContent: "Sin entidad" }));
    (typeof ENT !== "undefined" ? ENT : []).forEach(x => fArea.append(el("option", { value: x.nombre, textContent: x.nombre })));
    fArea.value = t.area || "";
    const fSub = el("input", { type: "text", value: subDe(t) || "" });
    const lista = el("datalist", { id: "accSubs" }); fSub.setAttribute("list", "accSubs");
    const llenarSubs = () => { const en = ent(fArea.value); lista.replaceChildren(...opcionesDe(en).map(o => el("option", { value: o }))); labSub.firstChild.textContent = en && en.etiqueta ? en.etiqueta : "Categoría"; };
    const labSub = el("label", {}, "Categoría", fSub, lista);
    fArea.onchange = llenarSubs; llenarSubs();
    const fHora = el("input", { type: "time", value: t.hora || "" });
    const fHoraFin = el("input", { type: "time", value: t.horaFin || "" });
    const guardar = el("button", { type: "submit", className: "btn solid", textContent: "Guardar" });
    const volver = el("button", { type: "button", className: "btn", textContent: "Volver" }); volver.onclick = () => menu(id);
    const form = el("form", { className: "acc-form" },
      el("label", {}, "Texto", fTexto),
      el("div", { className: "acc-fila" }, el("label", {}, "Fecha", fFecha), ev ? null : el("label", {}, "Prioridad", fPrio)),
      ev ? el("div", { className: "acc-fila" }, el("label", {}, "Hora de inicio", fHora), el("label", {}, "Hora de fin", fHoraFin)) : null,
      el("div", { className: "acc-fila" }, el("label", {}, "Entidad", fArea), labSub),
      el("label", {}, "Detalle", fNota),
      el("div", { className: "acc-pie" }, volver, guardar));
    form.onsubmit = x => {
      x.preventDefault();
      const texto = fTexto.value.trim(); if (!texto) { fTexto.focus(); return; }
      const en = ent(fArea.value), sub = fSub.value.trim() ? nombreSub(en, fSub.value) : null;
      const parcial = { texto, nota: fNota.value.trim(), fecha: fFecha.value || null, area: fArea.value || null };
      if (!ev) parcial.prioridad = fPrio.value || null;
      if (ev) { parcial.hora = fHora.value || null; parcial.horaFin = fHoraFin.value || null; }
      ["municipio", "tema", "sub"].forEach(k => { if (k in t) parcial[k] = null; });
      if (en && en.campo) parcial[en.campo] = sub; else if (sub) parcial.sub = sub;
      cambiar(id, parcial); cerrar();
    };
    dlg.replaceChildren(cabecera(t), form);
    fTexto.focus();
  }

  function mover(id) {
    const t = S.items.get(id); if (!t) return;
    const hoy = hoyIso();
    const a = f => { cambiar(id, { fecha: f }); cerrar(); };
    const otra = el("input", { type: "date", value: t.fecha || hoy });
    const ok = el("button", { type: "button", className: "btn solid", textContent: "Mover" }); ok.onclick = () => { if (otra.value) a(otra.value); };
    const volver = el("button", { type: "button", className: "btn", textContent: "Volver" }); volver.onclick = () => menu(id);
    const rap = (txt, f) => { const b = el("button", { type: "button", className: "chip", textContent: txt }); b.onclick = () => a(f); return b; };
    dlg.replaceChildren(cabecera(t), el("div", { className: "acc-form" },
      el("div", { className: "acc-rapidas" }, rap("Hoy", hoy), rap("Mañana", sumar(hoy, 1)), rap("Próximo lunes", proxLunes()), rap("En una semana", sumar(hoy, 7)), esEv(t) ? null : rap("Sin fecha", null)),
      el("label", {}, "Otra fecha", otra),
      el("div", { className: "acc-pie" }, volver, ok)));
  }

  function prioridad(id) {
    const t = S.items.get(id); if (!t) return;
    const a = p => { cambiar(id, { prioridad: p }); cerrar(); };
    dlg.replaceChildren(cabecera(t), el("div", { className: "acc-lista" },
      btn("🔴", "Urgente", () => a("alta")), btn("🟡", "Importante", () => a("media")),
      btn("🟢", "Puede esperar", () => a("baja")), btn("⚪", "Sin prioridad", () => a(null)), btn("←", "Volver", () => menu(id))));
  }

  function eliminar(id) {
    const t = S.items.get(id); if (!t) return;
    const si = el("button", { type: "button", className: "btn solid", textContent: "Sí, eliminar" });
    si.style.background = "var(--sem-r)"; si.style.borderColor = "var(--sem-r)"; si.style.color = "#fff";
    si.onclick = () => { quitarItem(id); cerrar(); };
    const no = el("button", { type: "button", className: "btn", textContent: "No, volver" }); no.onclick = () => menu(id);
    dlg.replaceChildren(cabecera(t), el("div", { className: "acc-form" },
      el("p", { className: "acc-aviso", textContent: "¿Eliminar este " + (esEv(t) ? "evento" : "pendiente") + "? No se puede deshacer. Si ya lo hiciste, es mejor marcarlo como atendido para que cuente en el tablero." }),
      el("div", { className: "acc-pie" }, no, si)));
    no.focus();
  }

  const hora = ms => new Intl.DateTimeFormat("es-CO", { day: "numeric", month: "short", hour: "numeric", minute: "2-digit" }).format(new Date(ms));

  function apuntes(id) {
    const t = S.items.get(id); if (!t) return;
    const lista = (t.apuntes || []).slice().sort((a, b) => b.t - a.t);
    const caja = el("textarea", { rows: 3, placeholder: "Qué hiciste, qué falta, a quién llamaste, decisiones…" });
    const guardar = el("button", { type: "button", className: "btn solid", textContent: "Guardar apunte" });
    guardar.onclick = () => {
      const v = caja.value.trim(); if (!v) { caja.focus(); return; }
      cambiar(id, { apuntes: [...(t.apuntes || []), { t: Date.now(), texto: v }] }); apuntes(id);
    };
    const volver = el("button", { type: "button", className: "btn", textContent: "Volver" }); volver.onclick = () => menu(id);
    const items = lista.map(a => {
      const x = el("button", { type: "button", className: "mini", textContent: "Borrar" });
      x.onclick = () => { cambiar(id, { apuntes: (t.apuntes || []).filter(b => b.t !== a.t) }); apuntes(id); };
      return el("li", { className: "apunte" }, el("div", {}, el("small", { textContent: hora(a.t) }), el("p", { textContent: a.texto })), x);
    });
    dlg.replaceChildren(cabecera(t), el("div", { className: "acc-form" },
      el("h3", { className: "acc-tit", textContent: "📝 Mis apuntes de trabajo" }),
      el("label", {}, "Nuevo apunte", caja),
      el("div", { className: "acc-pie" }, volver, guardar),
      items.length ? el("ul", { className: "apuntes" }, ...items) : el("p", { className: "acc-vacio", textContent: "Aún no tienes apuntes en este pendiente. Son solo para ti." })));
    if (!dlg.open) dlg.showModal();
    caja.focus();
  }

  function diego(id) {
    const t = S.items.get(id); if (!t) return;
    const d = t.diego || {};
    const preg = el("textarea", { rows: 3, value: d.pregunta || "", placeholder: "Qué necesito que Diego me defina o apruebe…" });
    const hablado = el("input", { type: "checkbox", checked: !!d.hablado });
    const linea = el("textarea", { rows: 3, value: d.linea || "", placeholder: "Qué dijo Diego, qué línea dio…" });
    const labLinea = el("label", {}, "Línea que dio Diego", linea);
    const ver = () => { labLinea.hidden = !hablado.checked; };
    hablado.onchange = ver; ver();
    const guardar = el("button", { type: "button", className: "btn solid", textContent: "Guardar" });
    guardar.onclick = () => {
      const nuevo = { pregunta: preg.value.trim(), hablado: hablado.checked, linea: linea.value.trim(), marcado: d.marcado || Date.now(), habladoEn: hablado.checked ? (d.habladoEn || Date.now()) : null };
      cambiar(id, { diego: nuevo }); cerrar();
    };
    const quitar = el("button", { type: "button", className: "btn", textContent: "Quitar de la lista" });
    quitar.onclick = () => { cambiar(id, { diego: null }); cerrar(); };
    const volver = el("button", { type: "button", className: "btn", textContent: "Volver" }); volver.onclick = () => menu(id);
    dlg.replaceChildren(cabecera(t), el("div", { className: "acc-form" },
      el("h3", { className: "acc-tit", textContent: "🗣️ Hablarlo con Diego" }),
      el("label", {}, "Qué le voy a plantear", preg),
      el("label", { className: "acc-check" }, hablado, "Ya lo hablé con Diego"),
      labLinea,
      el("div", { className: "acc-pie" }, volver, t.diego ? quitar : null, guardar)));
    if (!dlg.open) dlg.showModal();
    preg.focus();
  }

  function nuevoDiego() {
    const texto = el("input", { type: "text", placeholder: "Tema, por ejemplo: alcance del oficio a Montesacro" });
    const preg = el("textarea", { rows: 3, placeholder: "Qué necesito que Diego me defina o apruebe…" });
    const crear = el("button", { type: "button", className: "btn solid", textContent: "Agregar" });
    crear.onclick = () => {
      const v = texto.value.trim(); if (!v) { texto.focus(); return; }
      const id = "d" + Date.now().toString(36);
      ponerItem(id, { texto: v, fecha: hoyIso(), estado: "pendiente", nota: "", creado: Date.now(), area: ent("UAESP") ? "UAESP" : (S.area || null), municipio: null, tema: null, prioridad: null,
        diego: { pregunta: preg.value.trim(), hablado: false, linea: "", marcado: Date.now(), habladoEn: null } });
      cerrar();
    };
    const no = el("button", { type: "button", className: "btn", textContent: "Cancelar" }); no.onclick = cerrar;
    dlg.replaceChildren(el("div", { className: "acc-cab" }, el("b", { textContent: "🗣️ Nuevo tema para hablar con Diego" }), el("small", { textContent: "Queda como pendiente de la UAESP marcado para Diego." })),
      el("div", { className: "acc-form" }, el("label", {}, "Tema", texto), el("label", {}, "Qué le voy a plantear", preg), el("div", { className: "acc-pie" }, no, crear)));
    if (!dlg.open) dlg.showModal();
    texto.focus();
  }

  window.__accMenu = { menu, apuntes, diego, nuevoDiego };
  window.__decorar = () => {
    document.querySelectorAll("#contenido li.task").forEach(li => {
      const id = idDe(li), t = id && S.items.get(id); if (!t) return;
      li.querySelectorAll(".marca-extra, .coment-diego").forEach(x => x.remove());
      const marcas = [];
      if (t.diego) {
        const d = t.diego;
        const com = el("button", { type: "button", className: "coment-diego" + (d.hablado ? " hecho" : "") },
          el("b", { textContent: d.hablado ? "✅ Línea de Diego: " : "🗣️ Para hablar con Diego: " }),
          d.hablado ? (d.linea || d.pregunta || "sin detalle") : (d.pregunta || "toca para escribir qué le vas a plantear"));
        com.title = "Ver o editar el comentario para Diego";
        com.onclick = e => { e.stopPropagation(); diego(id); };
        const c = li.querySelector(".cuerpo"); if (c) c.append(com);
      } else if (!esEv(t) && t.estado === "pendiente" && (!t.area || t.area === "UAESP")) {
        const b = el("button", { type: "button", className: "marca-extra suave", textContent: "🗣️ + Diego" });
        b.title = "Agregar un comentario para hablar con Diego"; b.onclick = e => { e.stopPropagation(); diego(id); }; marcas.push(b);
      }
      if ((t.apuntes || []).length) {
        const b = el("button", { type: "button", className: "marca-extra", textContent: "📝 " + t.apuntes.length });
        b.title = "Mis apuntes"; b.onclick = e => { e.stopPropagation(); apuntes(id); }; marcas.push(b);
      }
      if (!marcas.length) return;
      let meta = li.querySelector(".meta");
      if (!meta) { meta = el("div", { className: "meta" }); const c = li.querySelector(".cuerpo"); if (!c) return; const tx = c.querySelector(".texto"); tx ? tx.after(meta) : c.prepend(meta); }
      meta.append(...marcas);
    });
  };
  window.__decorar();

  /* Detección del toque sostenido (y clic derecho en computador) */
  let timer = null, x0 = 0, y0 = 0, liAct = null, abrioMenu = false;
  const cancelar = () => { clearTimeout(timer); timer = null; if (liAct) liAct.classList.remove("presionando"); liAct = null; };
  contenido.addEventListener("pointerdown", e => {
    if (e.pointerType === "mouse" && e.button !== 0) return;
    const li = e.target.closest("li.task"); if (!li || e.target.closest("button, select, a")) return;
    const id = idDe(li); if (!id) return;
    cancelar(); liAct = li; x0 = e.clientX; y0 = e.clientY; abrioMenu = false;
    li.classList.add("presionando");
    timer = setTimeout(() => {
      li.classList.remove("presionando"); timer = null; abrioMenu = true;
      if (document.activeElement && document.activeElement.blur) document.activeElement.blur();
      try { window.getSelection().removeAllRanges(); } catch (x) {}
      if (navigator.vibrate) navigator.vibrate(15);
      menu(id);
    }, 550);
  });
  contenido.addEventListener("pointermove", e => { if (timer && Math.hypot(e.clientX - x0, e.clientY - y0) > 10) cancelar(); });
  ["pointerup", "pointercancel", "pointerleave"].forEach(n => contenido.addEventListener(n, () => { if (timer) cancelar(); }));
  window.addEventListener("scroll", () => { if (timer) cancelar(); }, { passive: true });
  contenido.addEventListener("contextmenu", e => {
    const li = e.target.closest("li.task"); if (!li) return;
    e.preventDefault();
    if (abrioMenu) { abrioMenu = false; return; }
    const id = idDe(li); if (id) { cancelar(); menu(id); }
  });
})();

/* ---------- Botón Buscar visible arriba ---------- */
(() => {
  const barra = document.querySelector(".filtros");
  const campo = barra && barra.querySelector("input[type=search]");
  if (!campo) return;
  const css = document.createElement("style");
  css.textContent = ".filtros .lupa { position: relative; flex: 1 1 100%; display: flex; }\n.filtros .lupa::before { content: \"🔍\"; position: absolute; left: 12px; top: 50%; transform: translateY(-50%); font-size: .95rem; pointer-events: none; }\n.filtros .lupa input[type=search] { flex: 1 1 auto; padding-left: 38px; min-height: 44px; border: 2px solid var(--accent); background: var(--soft); font-weight: 500; }\n.filtros .rotulo { flex: 1 1 100%; margin: 0; color: var(--ink); font-size: .8125rem; font-weight: 700; letter-spacing: .02em; }\n#btnBuscar { font-weight: 700; }";
  document.head.append(css);
  const lupa = document.createElement("div"); lupa.className = "lupa";
  campo.replaceWith(lupa); lupa.append(campo);
  campo.placeholder = "Palabra clave: oficio, Bojacá, Diego…";
  const rotulo = document.createElement("p"); rotulo.className = "rotulo"; rotulo.textContent = "Buscar y filtrar";
  barra.prepend(rotulo);
  const ir = () => { barra.scrollIntoView({ behavior: "smooth", block: "center" }); setTimeout(() => campo.focus(), 250); };
  const nav = document.querySelector("header .nav");
  if (nav) {
    const b = document.createElement("button");
    b.type = "button"; b.className = "btn"; b.id = "btnBuscar"; b.textContent = "🔍 Buscar";
    b.setAttribute("aria-label", "Buscar por palabra clave");
    b.onclick = ir; nav.append(b);
  }
  document.addEventListener("keydown", e => {
    const t = e.target, escribiendo = t && (t.isContentEditable || /^(INPUT|TEXTAREA|SELECT)$/.test(t.tagName));
    if (e.key === "/" && !escribiendo) { e.preventDefault(); ir(); }
  });
})();
