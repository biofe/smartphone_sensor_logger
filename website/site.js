(function () {
  // Version from the update manifest the app itself reads. Fails quietly (e.g. offline) and keeps "Newest version".
  // Contact address, assembled here so that simple address harvesters don't find it in the HTML.
  var contact = document.getElementById('contact');
  if (contact) {
    var addr = ['felix0180', 'web.de'].join('@');
    var a = document.createElement('a'); a.href = 'mailto:' + addr; a.textContent = addr;
    contact.textContent = ''; contact.appendChild(a);
  }

  var line = document.getElementById('version-line');
  if (window.fetch) {
    fetch('https://apk.velowind.de/latest.json', { cache: 'no-cache' })
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (j) { if (j && /^[0-9][0-9A-Za-z.\-]*$/.test(j.versionName || '')) line.textContent = (line.getAttribute('data-prefix') || 'Version') + ' ' + j.versionName; })
      .catch(function () {});
  }

  // Vibration along one real test ride (data in trace-data.js).
  var canvas = document.getElementById('trace');
  var data = window.VELOWIND_TRACE;
  if (!canvas || !data) return;
  var names = {};
  (canvas.getAttribute('data-labels') || '').split('|').forEach(function (pair) {
    var kv = pair.split('='); if (kv.length === 2) names[kv[0]] = kv[1];
  });
  var maxKm = Math.ceil(data.km[data.km.length - 1] * 10) / 10;
  var maxDb = Math.max.apply(null, data.db.map(Math.abs));

  function css(name) { return getComputedStyle(document.documentElement).getPropertyValue(name).trim(); }
  function mix(c1, c2, t) {
    function p(c) { c = c.replace('#', ''); return [0, 2, 4].map(function (o) { return parseInt(c.substr(o, 2), 16); }); }
    var a = p(c1), b = p(c2);
    return 'rgb(' + a.map(function (v, j) { return Math.round(v + (b[j] - v) * t); }).join(',') + ')';
  }

  function draw() {
    var dpr = window.devicePixelRatio || 1, w = canvas.clientWidth, h = canvas.clientHeight;
    if (!w || !h) return;
    canvas.width = Math.round(w * dpr); canvas.height = Math.round(h * dpr);
    var ctx = canvas.getContext('2d');
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, w, h);
    var teal = css('--teal'), rough = css('--rough'), muted = css('--muted'), line = css('--line'), ink = css('--ink');
    var axisW = 58, left = axisW, right = w - 2, top = w < 600 ? 34 : 22, bottom = h - 18, mid = (top + bottom) / 2;
    var x = function (km) { return left + (km / maxKm) * (right - left); };
    var y = function (db) { return mid - (db / maxDb) * (bottom - top) / 2; };
    ctx.font = '11px ' + css('--font-mono');

    // Vertical axis: relative dB, 0 = the ride's average at the same speed
    var yLabels = (canvas.getAttribute('data-axis') || 'rel. dB|average').split('|');
    ctx.fillStyle = muted; ctx.strokeStyle = line; ctx.lineWidth = 1;
    ctx.beginPath(); ctx.moveTo(left - 0.5, top); ctx.lineTo(left - 0.5, bottom); ctx.stroke();
    [-10, 10].forEach(function (v) {
      if (Math.abs(v) > maxDb) return;
      var ty = Math.round(y(v)) + 0.5;
      ctx.beginPath(); ctx.moveTo(left - 4, ty); ctx.lineTo(left, ty); ctx.stroke();
      var t = (v > 0 ? '+' : '−') + Math.abs(v), tw = ctx.measureText(t).width;
      ctx.fillText(t, left - 6 - tw, ty + 4);
    });
    var aw = ctx.measureText(yLabels[1]).width;
    ctx.fillText(yLabels[1], left - 6 - aw, Math.round(mid) + 4);
    ctx.fillText(yLabels[0], 0, top - 8);

    // The rider's surface ratings: a dashed line, labelled where it isn't plain asphalt
    var rowEnd = [-1, -1];
    data.marks.forEach(function (m) {
      var mx = Math.round(x(m.km)) + 0.5, special = true;
      ctx.strokeStyle = special ? muted : line; ctx.lineWidth = 1; ctx.setLineDash([2, 3]);
      ctx.beginPath(); ctx.moveTo(mx, top - 4); ctx.lineTo(mx, bottom); ctx.stroke(); ctx.setLineDash([]);
      if (special) {
        var label = names[m.surface] || m.surface, tw = ctx.measureText(label).width;
        var lx = Math.min(mx + 3, right - tw);
        var row = lx > rowEnd[0] + 6 ? 0 : 1;
        ctx.fillStyle = ink; ctx.fillText(label, lx, 12 + row * 12); rowEnd[row] = lx + tw;
      }
    });

    // One bar per 100 m: up = rougher, down = smoother than the ride's average at that speed
    var bw = Math.max(1, (right - left) / (maxKm * 10) - 1);
    for (var i = 0; i < data.km.length; i++) {
      var d = data.db[i], t = Math.max(0, Math.min(1, (d + 2) / 10));
      ctx.fillStyle = mix(teal, rough, t);
      var x0 = x(data.km[i]) - bw / 2, y0 = y(Math.max(d, 0)), y1 = y(Math.min(d, 0));
      ctx.fillRect(x0, y0, bw, Math.max(1, y1 - y0));
    }
    ctx.strokeStyle = muted; ctx.lineWidth = 1;
    ctx.beginPath(); ctx.moveTo(left, Math.round(mid) + 0.5); ctx.lineTo(right, Math.round(mid) + 0.5); ctx.stroke();

    // Distance axis
    ctx.fillStyle = muted;
    for (var k = 0; k <= Math.floor(maxKm); k += (w < 500 ? 2 : 1)) {
      var tx = x(k), txt = k + ' km', tw2 = ctx.measureText(txt).width;
      ctx.fillText(txt, Math.min(Math.max(tx - tw2 / 2, left), right - tw2), h - 3);
    }
  }

  draw();
  if (window.ResizeObserver) new ResizeObserver(draw).observe(canvas); else window.addEventListener('resize', draw);
  if (window.matchMedia) {
    var mq = window.matchMedia('(prefers-color-scheme: dark)');
    if (mq.addEventListener) mq.addEventListener('change', draw);
  }
  if (window.MutationObserver) new MutationObserver(draw).observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] });
})();
