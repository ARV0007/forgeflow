// Which emails and texts a customer has agreed to receive. Marketing is
// opt-in; order updates are always sent.
const NotificationPrefs = (() => {
  const DEFAULTS = { orderUpdates: true, offersByEmail: false, offersBySms: false, weeklyRecipes: false };

  function load(user) {
    return { ...DEFAULTS, ...Storage.load('prefs:' + user.email, {}) };
  }

  function update(user, changes) {
    const next = { ...load(user), ...changes, orderUpdates: true };
    Storage.save('prefs:' + user.email, next);
    return next;
  }

  function renderPreferencesForm(form, user) {
    const prefs = load(user);
    for (const [name, on] of Object.entries(prefs)) {
      if (form[name]) form[name].checked = on;
    }
    form.addEventListener('change', e => update(user, { [e.target.name]: e.target.checked }));
  }

  return { load, update, renderPreferencesForm };
})();
