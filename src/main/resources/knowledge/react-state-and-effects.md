<!-- stacks: react -->
# React state and effects

## Updating state from the previous value
When the next value depends on the current one, pass a function to the setter: `setCount(c => c + 1)`. Writing `setCount(count + 1)` twice in one handler adds one, not two, because both lines read the same stale `count` from that render. The function form always receives the latest value.

## State is replaced, not merged
`useState` does not merge objects the way old class components did. To change one field, copy the rest: `setForm(f => ({ ...f, email: value }))`. For arrays, build a new array instead of mutating: `setItems(list => [...list, item])`, `setItems(list => list.filter(i => i.id !== id))`. Mutating the existing array and calling the setter with the same reference does not re-render.

## Don't store what you can compute
If a value can be worked out from props or other state, compute it during render instead of keeping it in state: `const total = items.reduce((s, i) => s + i.price, 0)`. A second piece of state for the total has to be kept in sync by hand, and it eventually drifts.

## Effects that run too often
`useEffect(fn)` with no second argument runs after every render. `useEffect(fn, [])` runs once after the first render. `useEffect(fn, [query])` runs when `query` changes. An effect that sets state it also depends on, with that state in its dependency list, loops forever. Objects and functions created during render are new every time, so listing them as dependencies re-runs the effect on every render.

## Cleaning up
Return a function from the effect to undo what it started: clear an interval, remove an event listener, cancel a request. `useEffect(() => { const id = setInterval(tick, 1000); return () => clearInterval(id); }, [])`. Without cleanup, a component that mounts twice (React does this in development's strict mode) starts two timers.

## Effects are for syncing with the outside
Use an effect to talk to things outside React: timers, the network, localStorage, the document title. Responding to a click belongs in the click handler, not in an effect that watches state the click changed.
