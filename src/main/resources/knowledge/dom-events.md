<!-- stacks: static -->
# DOM scripting and events in plain JavaScript

## Running after the page has loaded
A script in the `<head>` runs before the elements below it exist, so `document.querySelector` returns null. Either put the script tag at the end of the body, or add `defer`: `<script src="app.js" defer></script>`. Module scripts (`type="module"`) are deferred automatically.

## Finding elements safely
`document.querySelector('#total')` returns null when nothing matches, and calling a method on null throws. Check the id matches the HTML exactly. Use `querySelectorAll` and `forEach` for many elements.

## Listening for events
Use `el.addEventListener('click', handler)` rather than inline `onclick` attributes. For a list whose items are added later, listen once on the parent and check the target (event delegation): `list.addEventListener('click', e => { const btn = e.target.closest('button.delete'); if (btn) remove(btn.dataset.id); });`

## Text versus HTML
Set user-provided text with `el.textContent = value`. Using `innerHTML` with text a user typed lets them inject HTML and scripts (cross-site scripting). Build elements with `document.createElement` when the content comes from input.

## Forms in plain JavaScript
Listen for `submit` on the form and call `event.preventDefault()` to stop the page reloading. Read values with `form.elements.name.value`, or `new FormData(form)`.

## Keeping state and rendering
Keep the data in one array or object, and write one `render()` function that rebuilds the list from it. Change the data, then call `render()`. Updating the DOM in many scattered places is how the page and the data fall out of sync.
