// Promo codes. Only one code can be active at a time; applying a new one
// replaces the old. A code can have a minimum spend and an expiry date.
const Discounts = (() => {
  const CODES = {
    WELCOME10: { type: 'percent', value: 10, minSpendPence: 0, expires: '2027-12-31' },
    FIVEOFF: { type: 'fixed', value: 500, minSpendPence: 3000, expires: '2027-06-30' },
    BAKERY20: { type: 'percent', value: 20, minSpendPence: 1000, expires: '2026-12-31', category: 'bakery' }
  };
  const KEY = 'promo';
  let active = Storage.load(KEY, null);

  function isExpired(rule, today = new Date()) {
    return new Date(rule.expires + 'T23:59:59') < today;
  }

  function applyPromoCode(raw) {
    const code = String(raw || '').trim().toUpperCase();
    const rule = CODES[code];
    if (!rule) return { ok: false, message: 'That promo code does not exist.' };
    if (isExpired(rule)) return { ok: false, message: 'That promo code has expired.' };
    if (Cart.subtotalPence() < rule.minSpendPence) {
      return { ok: false, message: 'Spend ' + Currency.formatPrice(rule.minSpendPence) + ' to use this code.' };
    }
    active = code;
    Storage.save(KEY, active);
    return { ok: true, message: code + ' applied.' };
  }

  // How much the active code takes off this subtotal, in pence.
  function discountPence(subtotal) {
    const rule = active && CODES[active];
    if (!rule || isExpired(rule) || subtotal < rule.minSpendPence) return 0;
    if (rule.category) {
      const eligible = Cart.lines()
        .filter(l => l.product.category === rule.category)
        .reduce((s, l) => s + l.product.pricePence * l.quantity, 0);
      return Math.round(eligible * rule.value / 100);
    }
    if (rule.type === 'percent') return Math.round(subtotal * rule.value / 100);
    return Math.min(rule.value, subtotal);
  }

  function clearPromo() {
    active = null;
    Storage.remove(KEY);
  }

  return { applyPromoCode, discountPence, clearPromo };
})();
