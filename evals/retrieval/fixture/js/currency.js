// Money is kept in whole pence everywhere to avoid floating point surprises
// (0.1 + 0.2). Only this file turns pence into something a person reads.
const Currency = (() => {
  const formatter = new Intl.NumberFormat('en-GB', { style: 'currency', currency: 'GBP' });

  function formatPrice(pence) {
    return formatter.format(pence / 100);
  }

  function toPence(pounds) {
    return Math.round(Number(pounds) * 100);
  }

  // "£2.50 each" or "£1.20 / kg" depending on how the product is sold.
  function formatUnitPrice(product) {
    const base = formatPrice(product.pricePence);
    return product.unit === 'kg' ? base + ' / kg' : base + ' each';
  }

  return { formatPrice, toPence, formatUnitPrice };
})();
