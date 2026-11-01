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

  // Illustrative vibration trace: asphalt, concrete slabs, asphalt, gravel, asphalt.
  var canvas = document.getElementById('trace');
  var labels = (canvas.getAttribute('data-labels') || 'asphalt|concrete slabs|gravel').split('|');
  var segments = [
    { from: 0.00, to: 0.22, label: labels[0], kind: 'smooth' },
    { from: 0.22, to: 0.42, label: labels[1], kind: 'slabs' },
    { from: 0.42, to: 0.55, label: labels[0], kind: 'smooth' },
    { from: 0.55, to: 0.86, label: labels[2], kind: 'gravel' },
    { from: 0.86, to: 1.00, label: labels[0], kind: 'smooth' }
  ];
  // Deterministic noise, so the picture is the same on every load.
  var seed = 7;
  function rnd() { seed = (seed * 16807) % 2147483647; return seed / 2147483647; }
  var N = 900, samples = [];
  for (var i = 0; i < N; i++) {
    var x = i / (N - 1), seg = segments[0];
    for (var k = 0; k < segments.length; k++) if (x >= segments[k].from && x <= segments[k].to) seg = segments[k];
    var base = (rnd() - 0.5) * 0.22 + Math.sin(i * 0.9) * 0.05;
    var a = base;
    if (seg.kind === 'slabs') { a = base * 1.2; if (i % 22 < 2) a += (i % 22 === 0 ? 1 : -0.8) * (0.45 + rnd() * 0.15); }
    if (seg.kind === 'gravel') { a = (rnd() - 0.5) * 0.75 + (rnd() - 0.5) * 0.25; }
    samples.push({ x: x, a: a, rough: seg.kind === 'gravel' ? 1 : seg.kind === 'slabs' ? 0.55 : 0 });
  }

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
    var teal = css('--teal'), rough = css('--rough'), muted = css('--muted'), line = css('--line');
    var top = 22, bandH = 8, plotTop = top + 4, plotBottom = h - bandH - 10, mid = (plotTop + plotBottom) / 2, amp = (plotBottom - plotTop) / 2;

    // Segment labels and dividers
    ctx.font = '12px ' + css('--font-mono');
    ctx.textBaseline = 'alphabetic';
    segments.forEach(function (s, idx) {
      var x0 = s.from * w, x1 = s.to * w;
      if (idx > 0) { ctx.strokeStyle = line; ctx.lineWidth = 1; ctx.setLineDash([3, 4]); ctx.beginPath(); ctx.moveTo(x0 + 0.5, 0); ctx.lineTo(x0 + 0.5, plotBottom); ctx.stroke(); ctx.setLineDash([]); }
      var tw = ctx.measureText(s.label).width;
      if (tw + 8 < x1 - x0) { ctx.fillStyle = muted; ctx.fillText(s.label, x0 + 6, 14); }
    });

    // Trace
    ctx.lineWidth = 1.2; ctx.lineJoin = 'round';
    for (var i = 1; i < samples.length; i++) {
      var p0 = samples[i - 1], p1 = samples[i];
      ctx.strokeStyle = mix(teal, rough, p1.rough);
      ctx.beginPath();
      ctx.moveTo(p0.x * w, mid - p0.a * amp);
      ctx.lineTo(p1.x * w, mid - p1.a * amp);
      ctx.stroke();
    }

    // Comfort band underneath
    segments.forEach(function (s) {
      var t = s.kind === 'gravel' ? 1 : s.kind === 'slabs' ? 0.55 : 0;
      ctx.fillStyle = mix(teal, rough, t);
      ctx.fillRect(s.from * w + 1, h - bandH, (s.to - s.from) * w - 2, bandH);
    });
  }

  draw();
  if (window.ResizeObserver) new ResizeObserver(draw).observe(canvas); else window.addEventListener('resize', draw);
  if (window.matchMedia) {
    var mq = window.matchMedia('(prefers-color-scheme: dark)');
    if (mq.addEventListener) mq.addEventListener('change', draw);
  }
  if (window.MutationObserver) new MutationObserver(draw).observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] });
})();
