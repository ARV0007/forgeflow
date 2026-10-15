<!-- stacks: static, react -->
# Accessibility basics

## Use the right element
A clickable thing that does something on the page is a `<button>`; one that goes somewhere is an `<a href>`. A `<div onclick>` can't be reached with the keyboard and isn't announced as a button by screen readers. Use `<nav>`, `<main>`, `<header>`, `<footer>` and headings in order (`h1`, then `h2`) so the page has a structure assistive technology can navigate.

## Labels
Every input needs a label tied to it: `<label for="email">Email</label><input id="email">` (in JSX, `htmlFor`). A placeholder is not a label: it disappears as soon as someone types. Icon-only buttons need an accessible name: `<button aria-label="Close">×</button>`.

## Images
Give meaningful images an `alt` that says what they show. Decorative images get `alt=""` so they are skipped.

## Keyboard and focus
Everything clickable must work with Tab and Enter or Space. Don't remove focus outlines without replacing them: `:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }`. A modal should move focus into itself when it opens and back when it closes; the native `<dialog>` element with `showModal()` does this for you.

## Colour and contrast
Body text needs a contrast ratio of at least 4.5:1 with its background. Never use colour alone to carry meaning (a red border for an error also needs an error message).

## Announcing changes
Content that updates without a page load, like a form error or "Added to cart", can be announced by putting it in an element with `aria-live="polite"`.
