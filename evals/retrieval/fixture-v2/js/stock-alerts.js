// "Email me when it's back": sold-out products offer a sign-up, and the list
// of people waiting is checked whenever stock is refilled.
const StockAlerts = (() => {
  const KEY = 'stock-alerts';

  function subscribeBackInStock(productId, email) {
    const alerts = Storage.load(KEY, {});
    alerts[productId] = [...new Set([...(alerts[productId] || []), email])];
    Storage.save(KEY, alerts);
  }

  // Called by the (pretend) warehouse sync when a product's stock goes above zero.
  function restocked(productId) {
    const alerts = Storage.load(KEY, {});
    const waiting = alerts[productId] || [];
    delete alerts[productId];
    Storage.save(KEY, alerts);
    return waiting.map(email => ({ to: email, subject: findProduct(productId).name + ' is back in stock' }));
  }

  return { subscribeBackInStock, restocked };
})();
