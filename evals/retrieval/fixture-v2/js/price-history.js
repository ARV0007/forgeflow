// Shows whether a product got cheaper or dearer over the last 30 days, as a
// percentage and a tiny sparkline.
const PriceHistory = (() => {
  const HISTORY = {
    banana: [20, 20, 19, 18, 18],
    cheddar: [350, 360, 375, 375, 375],
    sourdough: [320, 320, 320, 300, 320]
  };

  function priceChangePercent(productId) {
    const h = HISTORY[productId];
    if (!h || h.length < 2) return 0;
    return Math.round((h[h.length - 1] - h[0]) / h[0] * 100);
  }

  function sparkline(productId) {
    const h = HISTORY[productId] || [];
    const bars = '▁▂▃▄▅▆▇█';
    const min = Math.min(...h);
    const max = Math.max(...h);
    return h.map(v => bars[max === min ? 3 : Math.round((v - min) / (max - min) * 7)]).join('');
  }

  return { priceChangePercent, sparkline };
})();
