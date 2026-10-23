// Linked cursor for ride plots: hovering any time- or distance-based Plotly figure draws a
// vertical line at the same moment in every other figure and moves a marker on a floating map.
// Figures opt in with layout.meta.sync = [{xref: "x", yref: "paper", kind: "time" | "dist"}],
// where time is in minutes and distance in km since the start of the ride.
//
// initRideSync({track: {t, d, lat, lon}, mapStyle, annotations})
//   track:       per GPS fix, minutes (t), km (d), latitude and longitude
//   mapStyle:    Plotly map style name (e.g. "open-street-map") or a MapLibre style object
//   annotations: optional [{lat, lon, text}], drawn as green circles with the text on hover
(function () {
  if (window.initRideSync) return;

  window.initRideSync = function (opts) {
    const track = opts.track;
    const n = track.t.length;
    const linked = new Set();
    let mapDiv = null;
    let pending = null;

    // Linear interpolation on a monotonic array
    function interp(x, xs, ys) {
      if (x <= xs[0]) return ys[0];
      if (x >= xs[n - 1]) return ys[n - 1];
      let lo = 0, hi = n - 1;
      while (hi - lo > 1) {
        const mid = (lo + hi) >> 1;
        if (xs[mid] <= x) lo = mid; else hi = mid;
      }
      const f = xs[hi] === xs[lo] ? 0 : (x - xs[lo]) / (xs[hi] - xs[lo]);
      return ys[lo] + f * (ys[hi] - ys[lo]);
    }

    function cursorColor() {
      const v = getComputedStyle(document.documentElement).getPropertyValue("--accent").trim();
      return v || "#e8590c";
    }

    function moveTo(tMin, source) {
      pending = { tMin, source };
      requestAnimationFrame(() => {
        if (!pending) return;
        const { tMin, source } = pending;
        pending = null;
        const dKm = interp(tMin, track.t, track.d);
        const color = cursorColor();
        for (const gd of linked) {
          if (!gd.isConnected) { linked.delete(gd); continue; }
          if (gd === source) continue;
          const base = (gd.layout.shapes || []).filter((s) => s.name !== "ride-cursor");
          const lines = gd.layout.meta.sync.map((s) => ({
            type: "line", name: "ride-cursor", xref: s.xref, yref: s.yref,
            x0: s.kind === "time" ? tMin : dKm, x1: s.kind === "time" ? tMin : dKm, y0: 0, y1: 1,
            line: { color, width: 1.5 },
          }));
          window.Plotly.relayout(gd, { shapes: base.concat(lines) });
        }
        if (mapDiv) {
          window.Plotly.restyle(mapDiv, {
            lat: [[interp(tMin, track.t, track.lat)]], lon: [[interp(tMin, track.t, track.lon)]],
            text: [[`${tMin.toFixed(1)} min · ${dKm.toFixed(2)} km`]],
          }, [1]);
        }
      });
    }

    function attach(gd) {
      if (linked.has(gd) || !gd.layout || !gd.layout.meta || !gd.layout.meta.sync) return;
      linked.add(gd);
      gd.on("plotly_hover", (ev) => {
        const p = ev.points && ev.points[0];
        if (!p || !p.xaxis) return;
        const sync = gd.layout.meta.sync.find((s) => s.xref === p.xaxis._id) ||
          (gd.layout.meta.sync.length === 1 && p.xaxis._id.match(/^x\d*$/) && !gd.layout.meta.mixed ? gd.layout.meta.sync[0] : null);
        if (!sync || typeof p.x !== "number") return;
        const tMin = sync.kind === "time" ? p.x : interp(p.x, track.d, track.t);
        moveTo(tMin, null);
      });
    }

    function buildMap() {
      if (mapDiv || !window.Plotly) return;
      const panel = document.createElement("div");
      panel.className = "ride-map-panel";
      panel.innerHTML =
        '<div class="ride-map-bar"><span>Position</span><button type="button" aria-expanded="true">Hide</button></div>' +
        '<div class="ride-map"></div>';
      const style = document.createElement("style");
      style.textContent = `
        .ride-map-panel { position: fixed; z-index: 1000; right: max(12px, env(safe-area-inset-right, 0px));
          bottom: max(12px, env(safe-area-inset-bottom, 0px)); width: min(360px, calc(100vw - 24px));
          background: var(--panel, #fff); color: var(--fg, #222); border: 1px solid var(--rule, #ccc);
          box-shadow: 0 6px 24px rgba(0,0,0,.18); font: 12px system-ui, sans-serif; }
        .ride-map-bar { display: flex; justify-content: space-between; align-items: center; padding: 4px 8px; gap: 8px; }
        .ride-map-bar button { font: inherit; color: inherit; background: none; border: 1px solid var(--rule, #ccc); padding: 2px 8px; cursor: pointer; }
        .ride-map { height: 280px; }
        .ride-map-panel.collapsed .ride-map { display: none; }
        @media (max-width: 600px) { .ride-map { height: 190px; } }`;
      document.head.appendChild(style);
      document.body.appendChild(panel);
      mapDiv = panel.querySelector(".ride-map");
      const btn = panel.querySelector("button");
      btn.addEventListener("click", () => {
        const collapsed = panel.classList.toggle("collapsed");
        btn.textContent = collapsed ? "Show" : "Hide";
        btn.setAttribute("aria-expanded", String(!collapsed));
        if (!collapsed) window.Plotly.Plots.resize(mapDiv);
      });

      const lat = track.lat, lon = track.lon;
      const latMin = Math.min(...lat), latMax = Math.max(...lat), lonMin = Math.min(...lon), lonMax = Math.max(...lon);
      const span = Math.max(latMax - latMin, (lonMax - lonMin) * Math.cos((latMin + latMax) / 2 * Math.PI / 180));
      const zoom = Math.max(1, Math.min(16, Math.log2(360 / Math.max(span, 1e-4)) - 1.6));
      window.Plotly.newPlot(mapDiv, [
        { type: "scattermap", mode: "markers", lat, lon,
          marker: { size: 4, color: track.t, colorscale: "Viridis" },
          text: track.t.map((t, i) => `${t.toFixed(1)} min · ${track.d[i].toFixed(2)} km`), hoverinfo: "text" },
        { type: "scattermap", mode: "markers", lat: [lat[0]], lon: [lon[0]], text: ["start"], hoverinfo: "text",
          marker: { size: 16, color: cursorColor() } },
      ].concat((opts.annotations || []).length ? [{
        type: "scattermap", mode: "markers", hoverinfo: "text",
        lat: opts.annotations.map((a) => a.lat), lon: opts.annotations.map((a) => a.lon),
        text: opts.annotations.map((a) => a.text),
        marker: { size: 13, color: "#40c057", opacity: 0.85 },
      }] : []), {
        map: { style: opts.mapStyle, center: { lat: (latMin + latMax) / 2, lon: (lonMin + lonMax) / 2 }, zoom },
        margin: { l: 0, r: 0, t: 0, b: 0 }, showlegend: false, paper_bgcolor: "rgba(0,0,0,0)",
      }, { responsive: true, displaylogo: false, scrollZoom: true });
      mapDiv.on("plotly_hover", (ev) => {
        const p = ev.points && ev.points[0];
        if (p && p.curveNumber === 0) moveTo(track.t[p.pointIndex], null);
      });
    }

    function scan() {
      if (!window.Plotly) return;
      buildMap();
      document.querySelectorAll(".js-plotly-plot").forEach(attach);
    }

    // Figures can appear later (notebook cells, deferred drawing), so keep watching
    const timer = setInterval(scan, 1000);
    scan();
    return { refresh: scan, stop: () => clearInterval(timer), moveTo };
  };
})();
