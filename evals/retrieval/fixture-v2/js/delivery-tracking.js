// Live order tracking: where the driver is and when they'll arrive. The
// driver's position is simulated - a real app would poll the courier's API.
const DeliveryTracking = (() => {
  const AVERAGE_SPEED_KMH = 24;

  function distanceKm(a, b) {
    const R = 6371;
    const dLat = (b.lat - a.lat) * Math.PI / 180;
    const dLon = (b.lon - a.lon) * Math.PI / 180;
    const h = Math.sin(dLat / 2) ** 2 +
      Math.cos(a.lat * Math.PI / 180) * Math.cos(b.lat * Math.PI / 180) * Math.sin(dLon / 2) ** 2;
    return 2 * R * Math.asin(Math.sqrt(h));
  }

  function etaMinutes(driver, home) {
    return Math.ceil(distanceKm(driver, home) / AVERAGE_SPEED_KMH * 60);
  }

  function renderTracker(el, driver, home) {
    const mins = etaMinutes(driver, home);
    el.innerHTML = mins <= 1 ? 'Your driver is outside!' : `Your driver is about ${mins} minutes away.`;
  }

  return { etaMinutes, renderTracker };
})();
