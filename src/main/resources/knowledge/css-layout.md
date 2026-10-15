<!-- stacks: static, react -->
# CSS layout: flexbox and grid

## Centering something
To center a child both ways inside a box: `.box { display: grid; place-items: center; }`. With flexbox: `display: flex; justify-content: center; align-items: center;`. Vertical centering needs the container to have a height, for example `min-height: 100vh` for a full-screen hero.

## A row of items
Flexbox lays out one direction at a time: `.row { display: flex; gap: 16px; align-items: center; }`. `justify-content: space-between` pushes the first and last items to the edges, as in a header with a logo on the left and links on the right. Use `gap` for spacing instead of margins on every child.

## A responsive card grid
`.grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(240px, 1fr)); gap: 20px; }` fits as many 240px-or-wider columns as the screen allows and wraps the rest, with no media queries. Use `auto-fit` instead of `auto-fill` when a few cards should stretch to fill the row.

## Cards of equal height
Grid items in the same row are the same height by default. To pin a button to the bottom of each card, make the card a column flex container and push the button down: `.card { display: flex; flex-direction: column; } .card button { margin-top: auto; }`.

## A footer that stays at the bottom
`body { min-height: 100vh; display: flex; flex-direction: column; } main { flex: 1; }` keeps the footer at the bottom of the window on short pages and below the content on long ones.

## Box sizing
Start stylesheets with `*, *::before, *::after { box-sizing: border-box; }` so padding and borders are included in an element's width; otherwise `width: 100%` plus padding overflows its parent.
