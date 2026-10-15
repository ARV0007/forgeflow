<!-- stacks: static, react -->
# Fetching data and async code

## Checking the response
`fetch` only rejects on a network failure. A 404 or 500 still resolves, so check `res.ok` before reading the body: `const res = await fetch(url); if (!res.ok) throw new Error('Request failed: ' + res.status); const data = await res.json();`

## Loading, error and empty states
Every request has three outcomes the page must show: loading (a spinner or "Loading..."), failed (a message and a retry button), and done (the data, or an empty state if there is none). Keep each in state and render from it.

## async functions
`await` only works inside an `async` function (or at the top level of a module). Wrap awaited calls in `try { ... } catch (err) { ... }` to show an error instead of failing silently. Run independent requests together with `Promise.all([fetch(a), fetch(b)])` instead of one after the other.

## Requests in React effects
An effect can't be async itself; define an async function inside it and call it. Cancel the request if the component goes away or the input changes, so an old response can't overwrite a newer one: `useEffect(() => { const ctrl = new AbortController(); load(query, ctrl.signal); return () => ctrl.abort(); }, [query]);`

## Search as you type
Don't send a request on every keystroke. Wait until the user pauses (debounce): clear a timer on each keystroke and start a new one of about 300 ms that sends the request when it fires.

## Sending JSON
`fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload) })`.
