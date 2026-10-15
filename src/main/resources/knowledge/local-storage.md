<!-- stacks: static, react -->
# Saving data in localStorage

## Strings only
localStorage stores strings. Save objects with `localStorage.setItem('todos', JSON.stringify(todos))` and read them back with `JSON.parse(localStorage.getItem('todos'))`.

## Reading safely
`getItem` returns null for a missing key, and `JSON.parse` throws on corrupted text. Wrap the read and fall back to a default: `function load() { try { return JSON.parse(localStorage.getItem('todos')) ?? []; } catch { return []; } }`. Some browsers throw on any localStorage access in private mode or when storage is full, so wrap writes too.

## When to save
Save right after the data changes, in the same function that changes it, or in a React effect that watches the data: `useEffect(() => { localStorage.setItem('todos', JSON.stringify(todos)); }, [todos])`. In React, initialise state from storage lazily so it is read once: `useState(() => load())`.

## Keys and versions
Prefix keys with the app's name (`tipcalc.settings`) so two apps on the same origin don't collide. If the shape of saved data changes, store a version number and migrate or discard old data instead of crashing on it.

## Limits
Storage is about 5 MB per site, synchronous (it blocks while reading), and visible to any script on the page. Don't keep secrets or tokens in it, and don't use it for large data sets.
