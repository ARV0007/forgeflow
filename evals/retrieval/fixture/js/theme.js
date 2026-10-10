// Light / dark colour scheme. Starts from the operating system's preference,
// and a manual choice is remembered for next time.
(function () {
  const KEY = 'theme';
  const button = document.getElementById('theme-toggle');
  const prefersDark = window.matchMedia('(prefers-color-scheme: dark)');

  function applyTheme(theme) {
    document.documentElement.dataset.theme = theme;
    if (button) button.setAttribute('aria-pressed', theme === 'dark' ? 'true' : 'false');
  }

  const stored = Storage.load(KEY, null);
  applyTheme(stored || (prefersDark.matches ? 'dark' : 'light'));

  prefersDark.addEventListener('change', e => {
    if (!Storage.load(KEY, null)) applyTheme(e.matches ? 'dark' : 'light');
  });

  if (button) {
    button.addEventListener('click', () => {
      const next = document.documentElement.dataset.theme === 'dark' ? 'light' : 'dark';
      Storage.save(KEY, next);
      applyTheme(next);
    });
  }
})();
