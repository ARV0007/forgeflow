// Counts what people do, without sending anything anywhere: events are kept
// in a capped local log a developer can inspect in the console.
const Analytics = (() => {
  const KEY = 'events';
  const MAX_EVENTS = 200;

  function trackEvent(name, data = {}) {
    const log = Storage.load(KEY, []);
    log.push({ name, data, at: Date.now(), page: location.pathname });
    while (log.length > MAX_EVENTS) log.shift();
    Storage.save(KEY, log);
  }

  function summary() {
    const counts = {};
    for (const e of Storage.load(KEY, [])) counts[e.name] = (counts[e.name] || 0) + 1;
    return counts;
  }

  trackEvent('page_view');
  window.freshcartStats = summary;

  return { trackEvent, summary };
})();
