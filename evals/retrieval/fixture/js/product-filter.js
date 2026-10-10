// Search box, category dropdown, sort order and the in-stock checkbox on the
// shop page. Every change re-runs the whole pipeline: filter, then sort, then
// render - simple, and fast enough for a catalogue this size.
(function () {
  const grid = document.getElementById('product-grid');
  if (!grid) return;

  const searchBox = document.getElementById('search-box');
  const category = document.getElementById('category-filter');
  const sortOrder = document.getElementById('sort-order');
  const inStockOnly = document.getElementById('in-stock-only');
  const noResults = document.getElementById('no-results');

  function matchesSearch(product, term) {
    return !term || product.name.toLowerCase().includes(term.toLowerCase());
  }

  function sortProducts(list, order) {
    const copy = [...list];
    switch (order) {
      case 'price-asc': return copy.sort((a, b) => a.pricePence - b.pricePence);
      case 'price-desc': return copy.sort((a, b) => b.pricePence - a.pricePence);
      case 'name': return copy.sort((a, b) => a.name.localeCompare(b.name));
      default: return copy.sort((a, b) => b.popularity - a.popularity);
    }
  }

  function applyFilters() {
    const visible = PRODUCTS
      .filter(p => category.value === 'all' || p.category === category.value)
      .filter(p => matchesSearch(p, searchBox.value.trim()))
      .filter(p => !inStockOnly.checked || isInStock(p));
    const sorted = sortProducts(visible, sortOrder.value);
    renderProductGrid(grid, sorted);
    noResults.hidden = sorted.length > 0;
  }

  // Typing fires a lot of events; wait until the user pauses.
  let debounce;
  searchBox.addEventListener('input', () => {
    clearTimeout(debounce);
    debounce = setTimeout(applyFilters, 200);
  });
  [category, sortOrder, inStockOnly].forEach(el => el.addEventListener('change', applyFilters));

  applyFilters();
})();
