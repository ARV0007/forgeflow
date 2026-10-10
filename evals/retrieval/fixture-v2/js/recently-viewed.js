// The last eight products a shopper opened, newest first, shown as a strip
// under the product grid.
const RecentlyViewed = (() => {
  const KEY = 'recent';
  const MAX = 8;

  function recordView(productId) {
    const list = Storage.load(KEY, []).filter(id => id !== productId);
    list.unshift(productId);
    Storage.save(KEY, list.slice(0, MAX));
  }

  function recentProducts() {
    return Storage.load(KEY, []).map(findProduct).filter(Boolean);
  }

  function renderRecentStrip(el) {
    const products = recentProducts();
    el.hidden = products.length === 0;
    el.innerHTML = products.map(p => `<a href="#${p.id}">${p.name}</a>`).join('');
  }

  return { recordView, recentProducts, renderRecentStrip };
})();
