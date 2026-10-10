// A pretend sign-in: there is no server, so any email and a password of at
// least 8 characters "work". The session lives in storage and lasts 7 days.
const Auth = (() => {
  const KEY = 'session';
  const SESSION_DAYS = 7;

  function currentUser() {
    const session = Storage.load(KEY, null);
    if (!session) return null;
    if (Date.now() > session.expiresAt) {
      Storage.remove(KEY);
      return null;
    }
    return session.user;
  }

  function login(email, password) {
    if (Validation && Validation.validateEmail && Validation.validateEmail(email)) {
      return { ok: false, message: 'Enter a valid email address.' };
    }
    if (!password || password.length < 8) {
      return { ok: false, message: 'Passwords are at least 8 characters.' };
    }
    const name = email.split('@')[0];
    Storage.save(KEY, { user: { email, name }, expiresAt: Date.now() + SESSION_DAYS * 86400000 });
    return { ok: true };
  }

  function logout() {
    Storage.remove(KEY);
  }

  function renderAccountPage() {
    const user = currentUser();
    document.getElementById('signed-out').hidden = !!user;
    document.getElementById('signed-in').hidden = !user;
    if (user) document.getElementById('account-name').textContent = user.name;
  }

  const form = document.getElementById('login-form');
  if (form) {
    form.addEventListener('submit', e => {
      e.preventDefault();
      const result = login(form.email.value, form.password.value);
      if (!result.ok) Toast.show(result.message, 'error');
      renderAccountPage();
    });
    document.getElementById('logout').addEventListener('click', () => {
      logout();
      renderAccountPage();
    });
    renderAccountPage();
  }

  return { currentUser, login, logout };
})();
