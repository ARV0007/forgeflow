// After 15 minutes with no clicks or key presses, warn the shopper; after 2
// more, sign them out so a shared computer isn't left logged in.
(function () {
  const IDLE_WARNING_MS = 15 * 60 * 1000;
  const GRACE_MS = 2 * 60 * 1000;
  let warnTimer;
  let logoutTimer;

  function resetIdleTimers() {
    clearTimeout(warnTimer);
    clearTimeout(logoutTimer);
    warnTimer = setTimeout(() => {
      Toast.show('You will be signed out in 2 minutes unless you do something.', 'error');
      logoutTimer = setTimeout(() => {
        if (typeof Auth !== 'undefined') Auth.logout();
        location.href = 'account.html';
      }, GRACE_MS);
    }, IDLE_WARNING_MS);
  }

  ['click', 'keydown', 'scroll'].forEach(evt => document.addEventListener(evt, resetIdleTimers, { passive: true }));
  resetIdleTimers();
})();
