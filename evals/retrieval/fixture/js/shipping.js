// Delivery charges. Orders over the threshold go free; below it there is a
// flat fee, plus a surcharge for the evening slot.
const Shipping = (() => {
  const FREE_OVER_PENCE = 4000;
  const FLAT_FEE_PENCE = 399;
  const EVENING_SURCHARGE_PENCE = 150;

  function costPence(orderPence, slot = 'asap') {
    if (orderPence === 0) return 0;
    const base = orderPence >= FREE_OVER_PENCE ? 0 : FLAT_FEE_PENCE;
    return base + (slot === 'evening' ? EVENING_SURCHARGE_PENCE : 0);
  }

  function amountToFreeDelivery(orderPence) {
    return Math.max(0, FREE_OVER_PENCE - orderPence);
  }

  return { costPence, amountToFreeDelivery };
})();
