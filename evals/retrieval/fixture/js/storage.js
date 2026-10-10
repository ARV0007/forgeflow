// Thin wrapper over localStorage so the rest of the app never touches it
// directly. Everything is stored as JSON under a "freshcart:" prefix, and a
// browser that blocks storage (private mode) just gets an in-memory fallback.
const Storage = (() => {
  const PREFIX = 'freshcart:';
  const memory = new Map();

  function available() {
    try {
      localStorage.setItem(PREFIX + 'probe', '1');
      localStorage.removeItem(PREFIX + 'probe');
      return true;
    } catch (e) {
      return false;
    }
  }

  const useLocal = available();

  function load(key, fallback) {
    const raw = useLocal ? localStorage.getItem(PREFIX + key) : memory.get(key);
    if (raw == null) return fallback;
    try {
      return JSON.parse(raw);
    } catch (e) {
      return fallback;
    }
  }

  function save(key, value) {
    const raw = JSON.stringify(value);
    if (useLocal) localStorage.setItem(PREFIX + key, raw);
    else memory.set(key, raw);
  }

  function remove(key) {
    if (useLocal) localStorage.removeItem(PREFIX + key);
    else memory.delete(key);
  }

  return { load, save, remove };
})();
