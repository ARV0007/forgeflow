// Checkout: validates the form, works out the final total, "places" the order
// (there is no real payment provider here) and shows a confirmation.
(function () {
  const form = document.getElementById('checkout-form');
  if (!form) return;

  function orderTotals(slot) {
    const subtotal = Cart.subtotalPence();
    const discount = Discounts.discountPence(subtotal);
    const shipping = Shipping.costPence(subtotal - discount, slot);
    return { subtotal, discount, shipping, total: subtotal - discount + shipping };
  }

  function showTotal() {
    document.getElementById('checkout-total').textContent =
      Currency.formatPrice(orderTotals(form.slot.value).total);
  }

  function validateCheckoutForm() {
    const checks = [
      [form.email, Validation.validateEmail(form.email.value)],
      [form.postcode, Validation.validatePostcode(form.postcode.value)],
      [form.cardNumber, Validation.validateCardNumber(form.cardNumber.value)],
      [form.expiry, Validation.validateExpiry(form.expiry.value)]
    ];
    checks.forEach(([input, error]) => Validation.showFieldError(input, error));
    return checks.every(([, error]) => !error);
  }

  function generateOrderNumber() {
    return 'FC-' + Date.now().toString(36).toUpperCase();
  }

  form.slot.addEventListener('change', showTotal);

  form.addEventListener('submit', e => {
    e.preventDefault();
    if (Cart.itemCount() === 0) {
      Toast.show('Your cart is empty.', 'error');
      return;
    }
    if (!validateCheckoutForm()) {
      Toast.show('Please fix the highlighted fields.', 'error');
      return;
    }
    const order = {
      number: generateOrderNumber(),
      placedAt: new Date().toISOString(),
      lines: Cart.lines().map(l => ({ id: l.productId, name: l.product.name, quantity: l.quantity })),
      ...orderTotals(form.slot.value)
    };
    const history = Storage.load('orders', []);
    history.unshift(order);
    Storage.save('orders', history);
    Cart.clearCart();
    Discounts.clearPromo();

    form.hidden = true;
    const done = document.getElementById('order-confirmation');
    done.hidden = false;
    done.innerHTML = `<h2>Thank you!</h2><p>Order ${order.number} is on its way.
      You paid ${Currency.formatPrice(order.total)}.</p>`;
  });

  showTotal();
})();
