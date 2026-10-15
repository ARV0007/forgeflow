<!-- stacks: static, react -->
# Responsive design

## The viewport tag
Every page needs `<meta name="viewport" content="width=device-width, initial-scale=1">` in its head. Without it, phones render the page at desktop width and shrink it, and media queries don't fire.

## Mobile first
Write the base styles for small screens, then add wider layouts with `min-width` media queries: `@media (min-width: 768px) { .grid { grid-template-columns: 1fr 1fr; } }`. Typical breakpoints are 640px, 768px and 1024px, but choose them where the design actually breaks.

## Fluid type and spacing
`font-size: clamp(1.5rem, 4vw, 3rem)` scales a heading with the screen but never below or above the limits. Use `rem` for type so it respects the user's browser setting.

## Images
`img { max-width: 100%; height: auto; display: block; }` stops images overflowing their container. For a cover image of fixed shape, use `aspect-ratio: 16 / 9; object-fit: cover;`.

## Touch targets and tables
Make buttons and links at least 44px tall on touch screens. Wide tables should scroll inside a wrapper (`overflow-x: auto`) instead of widening the whole page.
