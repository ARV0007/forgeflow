// Drop-down of product names under the search box once two letters are
// typed. Arrow keys move through the list; Enter picks.
(function () {
  const box = document.getElementById('search-box');
  if (!box) return;
  const list = document.createElement('ul');
  list.className = 'suggestions';
  box.after(list);
  let active = -1;

  function suggestionsFor(text) {
    if (text.length < 2) return [];
    const t = text.toLowerCase();
    return PRODUCTS.filter(p => p.name.toLowerCase().startsWith(t)).slice(0, 6);
  }

  function showSuggestions() {
    const items = suggestionsFor(box.value.trim());
    active = -1;
    list.innerHTML = items.map(p => `<li data-name="${p.name}">${p.name}</li>`).join('');
  }

  box.addEventListener('keyup', e => {
    const items = list.querySelectorAll('li');
    if (e.key === 'ArrowDown') active = Math.min(active + 1, items.length - 1);
    else if (e.key === 'ArrowUp') active = Math.max(active - 1, 0);
    else if (e.key === 'Enter' && items[active]) box.value = items[active].dataset.name;
    else return showSuggestions();
    items.forEach((li, i) => li.classList.toggle('active', i === active));
  });
})();
