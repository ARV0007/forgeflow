<!-- stacks: static, react -->
# Dark mode and theming

## Colours as custom properties
Define colours once as variables and use them everywhere: `:root { --bg: #ffffff; --text: #1a1a1a; --accent: #2563eb; } body { background: var(--bg); color: var(--text); }`. A theme is then just a different set of values for the same variables.

## Following the system setting
`@media (prefers-color-scheme: dark) { :root { --bg: #121212; --text: #ececec; } }` switches automatically with the operating system. Add `color-scheme: light dark;` on `:root` so form controls and scrollbars match.

## A toggle button
Put the choice on the root element and override the variables for it: `:root[data-theme="dark"] { --bg: #121212; --text: #ececec; }`. The button sets `document.documentElement.dataset.theme = 'dark'` (or `'light'`) and saves the choice in localStorage.

## Avoiding the flash of the wrong theme
Read the saved choice in a small inline script in the `<head>`, before the stylesheet paints: `<script>const t = localStorage.getItem('theme'); if (t) document.documentElement.dataset.theme = t;</script>`. Setting it later, from a script at the end of the body, shows the light page for a moment first.

## Dark colours that work
Don't use pure black backgrounds with pure white text; a very dark grey with an off-white is easier to read. Check that accent colours still have enough contrast on the dark background, and soften large bright surfaces.
