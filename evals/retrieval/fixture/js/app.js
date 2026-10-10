// Page start-up shared by every page: header badge, translations, the
// featured products on the home page, and the add-to-cart buttons.
(function () {
  Cart.updateCartBadge();
  if (typeof I18n !== 'undefined') I18n.translatePage();

  const featured = document.getElementById('featured-grid');
  if (featured) {
    const top = [...PRODUCTS].filter(isInStock).sort((a, b) => b.popularity - a.popularity).slice(0, 4);
    renderProductGrid(featured, top);
  }

  // One listener for every card on the page, including cards drawn later.
  document.addEventListener('click', e => {
    const button = e.target.closest('.add-to-cart');
    if (!button) return;
    const id = button.closest('.product-card').dataset.id;
    if (Cart.addToCart(id) && typeof Analytics !== 'undefined') {
      Analytics.trackEvent('add_to_cart', { id });
    }
  });
})();
