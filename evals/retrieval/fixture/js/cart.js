// The cart: a list of { productId, quantity } kept in storage so it survives
// a page reload. Totals are always recomputed from the list, never stored.
const Cart = (() => {
  const KEY = 'cart';
  const MAX_QUANTITY = 20;
  let items = Storage.load(KEY, []);

  function persist() {
    Storage.save(KEY, items);
    updateCartBadge();
  }

  function addToCart(productId, quantity = 1) {
    const product = findProduct(productId);
    if (!product || !isInStock(product)) return false;
    const line = items.find(i => i.productId === productId);
    if (line) line.quantity = Math.min(line.quantity + quantity, MAX_QUANTITY);
    else items.push({ productId, quantity });
    persist();
    Toast.show(product.name + ' added to your cart');
    return true;
  }

  function setQuantity(productId, quantity) {
    if (quantity <= 0) return removeFromCart(productId);
    const line = items.find(i => i.productId === productId);
    if (line) {
      line.quantity = Math.min(quantity, MAX_QUANTITY);
      persist();
    }
  }

  function removeFromCart(productId) {
    items = items.filter(i => i.productId !== productId);
    persist();
  }

  function clearCart() {
    items = [];
    persist();
  }

  function lines() {
    return items
      .map(i => ({ ...i, product: findProduct(i.productId) }))
      .filter(l => l.product);
  }

  function subtotalPence() {
    return lines().reduce((sum, l) => sum + l.product.pricePence * l.quantity, 0);
  }

  function itemCount() {
    return items.reduce((n, i) => n + i.quantity, 0);
  }

  // The little number next to "Cart" in the header.
  function updateCartBadge() {
    const badge = document.getElementById('cart-count');
    if (badge) badge.textContent = itemCount();
  }

  return { addToCart, setQuantity, removeFromCart, clearCart, lines, subtotalPence, itemCount, updateCartBadge };
})();
