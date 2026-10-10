// The catalogue. In a real shop this would come from an API; here it is a
// fixed list so the site works when opened straight from disk.
const PRODUCTS = [
  { id: 'apple-gala', name: 'Gala apples', category: 'fruit', pricePence: 45, unit: 'each', stock: 120, popularity: 95, image: 'apple.jpg' },
  { id: 'banana', name: 'Bananas', category: 'fruit', pricePence: 18, unit: 'each', stock: 300, popularity: 99, image: 'banana.jpg' },
  { id: 'strawberries', name: 'Strawberries 400g', category: 'fruit', pricePence: 250, unit: 'each', stock: 0, popularity: 80, image: 'strawberry.jpg' },
  { id: 'carrots', name: 'Carrots', category: 'vegetables', pricePence: 90, unit: 'kg', stock: 60, popularity: 70, image: 'carrot.jpg' },
  { id: 'broccoli', name: 'Broccoli', category: 'vegetables', pricePence: 65, unit: 'each', stock: 40, popularity: 66, image: 'broccoli.jpg' },
  { id: 'sourdough', name: 'Sourdough loaf', category: 'bakery', pricePence: 320, unit: 'each', stock: 12, popularity: 88, image: 'sourdough.jpg' },
  { id: 'croissant', name: 'Butter croissant', category: 'bakery', pricePence: 110, unit: 'each', stock: 30, popularity: 75, image: 'croissant.jpg' },
  { id: 'milk', name: 'Whole milk 2L', category: 'dairy', pricePence: 155, unit: 'each', stock: 80, popularity: 92, image: 'milk.jpg' },
  { id: 'cheddar', name: 'Mature cheddar 400g', category: 'dairy', pricePence: 375, unit: 'each', stock: 25, popularity: 78, image: 'cheddar.jpg' }
];

function findProduct(id) {
  return PRODUCTS.find(p => p.id === id);
}

function isInStock(product) {
  return product.stock > 0;
}

function productCardHtml(product) {
  const soldOut = !isInStock(product);
  return `
    <article class="product-card${soldOut ? ' sold-out' : ''}" data-id="${product.id}">
      <img src="img/${product.image}" alt="${product.name}" loading="lazy">
      <h3>${product.name}</h3>
      <p class="price">${Currency.formatUnitPrice(product)}</p>
      <button class="add-to-cart" ${soldOut ? 'disabled' : ''}>${soldOut ? 'Sold out' : 'Add to cart'}</button>
      <button class="save-for-later" aria-label="Save ${product.name} for later">&#9825;</button>
    </article>`;
}

// Fills a grid element with cards for the given products.
function renderProductGrid(container, products) {
  container.innerHTML = products.map(productCardHtml).join('');
}
