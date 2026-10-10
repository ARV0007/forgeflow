// Gift cards: a 16-character code with a balance. Unlike promo codes, a gift
// card is money - it pays for part of the order and its balance goes down.
const GiftCards = (() => {
  const CARDS = {
    'FRSH-2024-ABCD-0001': { balancePence: 2500 },
    'FRSH-2024-ABCD-0002': { balancePence: 1000 }
  };

  function normaliseCardCode(raw) {
    return String(raw || '').replace(/[^A-Z0-9]/gi, '').toUpperCase().replace(/(.{4})(?=.)/g, '$1-');
  }

  function balanceOf(code) {
    const card = CARDS[normaliseCardCode(code)];
    return card ? card.balancePence : null;
  }

  // Takes as much of the order total as the card can cover.
  function redeemGiftCard(code, orderTotalPence) {
    const card = CARDS[normaliseCardCode(code)];
    if (!card) return { ok: false, message: 'Gift card not recognised.' };
    if (card.balancePence === 0) return { ok: false, message: 'This gift card has no balance left.' };
    const used = Math.min(card.balancePence, orderTotalPence);
    card.balancePence -= used;
    return { ok: true, usedPence: used, remainingPence: card.balancePence };
  }

  return { balanceOf, redeemGiftCard };
})();
