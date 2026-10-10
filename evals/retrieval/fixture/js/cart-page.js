// Draws the cart table and the summary box on cart.html, and wires up the
// quantity inputs, remove buttons and the promo code form.
(function () {
  const rows = document.getElementById('cart-rows');
  if (!rows) return;

  function renderCartTable() {
    const lines = Cart.lines();
    document.getElementById('cart-empty').hidden = lines.length > 0;
    rows.innerHTML = lines.map(l => `
      <tr data-id="${l.productId}">
        <td>${l.product.name}</td>
        <td>${Currency.formatPrice(l.product.pricePence)}</td>
        <td><input class="qty" type="number" min="0" max="20" value="${l.quantity}"></td>
        <td>${Currency.formatPrice(l.product.pricePence * l.quantity)}</td>
        <td><button class="remove" aria-label="Remove">&times;</button></td>
      </tr>`).join('');
    renderSummary();
  }

  function renderSummary() {
    const subtotal = Cart.subtotalPence();
    const discount = Discounts.discountPence(subtotal);
    const shipping = Shipping.costPence(subtotal - discount);
    document.getElementById('subtotal').textContent = Currency.formatPrice(subtotal);
    document.getElementById('discount').textContent = discount ? '-' + Currency.formatPrice(discount) : '-';
    document.getElementById('shipping').textContent = shipping ? Currency.formatPrice(shipping) : 'Free';
    document.getElementById('grand-total').textContent = Currency.formatPrice(subtotal - discount + shipping);
  }

  rows.addEventListener('change', e => {
    if (!e.target.classList.contains('qty')) return;
    const id = e.target.closest('tr').dataset.id;
    Cart.setQuantity(id, parseInt(e.target.value, 10) || 0);
    renderCartTable();
  });

  rows.addEventListener('click', e => {
    if (!e.target.classList.contains('remove')) return;
    Cart.removeFromCart(e.target.closest('tr').dataset.id);
    renderCartTable();
  });

  document.getElementById('promo-form').addEventListener('submit', e => {
    e.preventDefault();
    const result = Discounts.applyPromoCode(document.getElementById('promo-code').value);
    Toast.show(result.message, result.ok ? 'success' : 'error');
    renderSummary();
  });

  renderCartTable();
})();
