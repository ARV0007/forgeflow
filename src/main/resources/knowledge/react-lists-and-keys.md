<!-- stacks: react -->
# Lists, keys and conditional rendering in React

## Rendering a list
Map the array to elements: `{items.map(item => <li key={item.id}>{item.name}</li>)}`. Each element needs a `key` that is unique among its siblings.

## Choosing a key
Use a stable id that belongs to the data: a database id, a slug, or an id created once when the item is added (`crypto.randomUUID()`). Don't use the array index when items can be added, removed or reordered: React matches old and new elements by key, so an index key makes a deleted row's input text appear in the row below it. Never use `Math.random()` as a key; it changes every render and remounts everything.

## Empty states
Show something when the list is empty instead of rendering nothing: `{items.length === 0 ? <p>No tasks yet.</p> : <ul>...</ul>}`.

## Conditional rendering
`{isOpen && <Menu />}` renders the menu only when `isOpen` is true. Be careful with numbers: `{count && <Badge />}` renders a literal `0` when count is zero. Write `{count > 0 && <Badge />}` instead.

## Lifting state up
When two sibling components need the same data, keep the state in their closest common parent and pass it down as props, with a callback to change it: `<TodoList items={items} onToggle={toggle} />`.
