// "Save for later": a heart button on each product card. Saved product ids
// are kept in storage and listed on the account page.
const Wishlist = (() => {
  const KEY = 'wishlist';
  let saved = new Set(Storage.load(KEY, []));

  function toggleWishlist(productId) {
    if (saved.has(productId)) saved.delete(productId);
    else saved.add(productId);
    Storage.save(KEY, [...saved]);
    return saved.has(productId);
  }

  function isSaved(productId) {
    return saved.has(productId);
  }

  document.addEventListener('click', e => {
    const button = e.target.closest('.save-for-later');
    if (!button) return;
    const id = button.closest('.product-card').dataset.id;
    const nowSaved = toggleWishlist(id);
    button.classList.toggle('saved', nowSaved);
    Toast.show(nowSaved ? 'Saved for later' : 'Removed from saved items');
  });

  const listEl = document.getElementById('wishlist-items');
  if (listEl) {
    listEl.innerHTML = [...saved].map(id => findProduct(id)).filter(Boolean)
      .map(p => `<li>${p.name} - ${Currency.formatUnitPrice(p)}</li>`).join('') || '<li>Nothing saved yet.</li>';
  }

  return { toggleWishlist, isSaved };
})();
