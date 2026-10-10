// Lists previous orders on the account page, newest first, with a button to
// put everything from an old order back in the cart.
(function () {
  const list = document.getElementById('order-history');
  if (!list) return;

  function formatOrderDate(iso) {
    return new Date(iso).toLocaleDateString('en-GB', { day: 'numeric', month: 'long', year: 'numeric' });
  }

  function renderOrderHistory() {
    const orders = Storage.load('orders', []);
    if (orders.length === 0) {
      list.innerHTML = '<li>No orders yet.</li>';
      return;
    }
    list.innerHTML = orders.map(o => `
      <li data-number="${o.number}">
        <strong>${o.number}</strong> - ${formatOrderDate(o.placedAt)} - ${Currency.formatPrice(o.total)}
        <button class="reorder">Buy again</button>
      </li>`).join('');
  }

  list.addEventListener('click', e => {
    if (!e.target.classList.contains('reorder')) return;
    const number = e.target.closest('li').dataset.number;
    const order = Storage.load('orders', []).find(o => o.number === number);
    if (!order) return;
    order.lines.forEach(l => Cart.addToCart(l.id, l.quantity));
  });

  renderOrderHistory();
})();
