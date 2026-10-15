<!-- stacks: static, react -->
# Transitions and animation

## Animate transform and opacity
Transitions on `transform` and `opacity` are smooth because the browser can run them without re-laying out the page. Animating `width`, `height`, `top` or `left` forces layout on every frame and stutters. Move things with `transform: translateY(-4px)` and scale with `transform: scale(1.05)`.

## Hover and focus effects
`.card { transition: transform 150ms ease, box-shadow 150ms ease; } .card:hover, .card:focus-within { transform: translateY(-2px); }`. Keep interface transitions short, between 100 and 300 ms.

## Keyframes
`@keyframes fade-in { from { opacity: 0; transform: translateY(8px); } to { opacity: 1; transform: none; } } .item { animation: fade-in 200ms ease-out both; }`

## Respecting reduced motion
Some people turn off motion in their operating system because it makes them unwell. Honour it: `@media (prefers-reduced-motion: reduce) { *, *::before, *::after { animation-duration: 0.01ms !important; transition-duration: 0.01ms !important; } }`

## Showing and hiding
`display: none` can't be transitioned. To fade something out, transition `opacity` (and `visibility`), then remove it, or toggle a class that changes both.
