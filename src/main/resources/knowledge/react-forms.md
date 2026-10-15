<!-- stacks: react -->
# Forms in React

## Controlled inputs
Keep the input's value in state and update it on change: `<input value={name} onChange={e => setName(e.target.value)} />`. For a checkbox, use `checked={done}` and `e.target.checked`. An input with `value` but no `onChange` becomes read-only.

## Submitting
Handle submit on the form, not the button, so Enter works too: `<form onSubmit={handleSubmit}>`. Call `e.preventDefault()` first, or the browser reloads the page. Reset the fields after a successful submit by setting their state back to empty.

## Validation
Check the values in the submit handler and keep the error messages in state, shown next to the field they belong to. Disable the submit button while a request is in flight so it can't be sent twice. Built-in attributes like `required`, `type="email"` and `min` still work and give free browser messages.

## Numbers from inputs
An input's value is always a string, even with `type="number"`. Convert before doing arithmetic: `Number(e.target.value)` or `parseFloat`, and handle the empty string (an empty field is not zero).
