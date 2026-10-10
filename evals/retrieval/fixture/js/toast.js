// Small pop-up notices in the corner ("Added to your cart"). They stack, and
// each one fades away on its own after a few seconds.
const Toast = (() => {
  const DURATION_MS = 3000;

  function show(message, kind = 'info') {
    const root = document.getElementById('toast-root');
    if (!root) return;
    const el = document.createElement('div');
    el.className = 'toast toast-' + kind;
    el.setAttribute('role', kind === 'error' ? 'alert' : 'status');
    el.textContent = message;
    root.appendChild(el);
    requestAnimationFrame(() => el.classList.add('visible'));
    setTimeout(() => {
      el.classList.remove('visible');
      el.addEventListener('transitionend', () => el.remove(), { once: true });
    }, DURATION_MS);
  }

  return { show };
})();
