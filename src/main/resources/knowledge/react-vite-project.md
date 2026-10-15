<!-- stacks: react -->
# A React project built with Vite

## The files that must exist
- `package.json` with `react` and `react-dom` in dependencies, and `vite` plus `@vitejs/plugin-react` in devDependencies, and scripts `"dev": "vite"` and `"build": "vite build"`.
- `vite.config.js`: `import { defineConfig } from 'vite'; import react from '@vitejs/plugin-react'; export default defineConfig({ plugins: [react()] });`
- `index.html` at the project root, containing `<div id="root"></div>` and `<script type="module" src="/src/main.jsx"></script>`.
- `src/main.jsx`, which mounts the app.

## Mounting the app
In `src/main.jsx`: `import { createRoot } from 'react-dom/client'; import App from './App.jsx'; import './index.css'; createRoot(document.getElementById('root')).render(<App />);` The old `ReactDOM.render` is gone in React 18 and later.

## Imports
Relative imports must point at real files: `import Card from './components/Card'` needs `src/components/Card.jsx` (or `.js`). Every package you import from must be listed in `package.json`. Components are default-exported functions whose names start with a capital letter; a lowercase name is treated as an HTML tag.

## CSS
Import a stylesheet from JavaScript (`import './index.css'`) and Vite injects it. Use `className`, not `class`, in JSX, and `htmlFor` instead of `for` on labels.

## Static files
Files in `public/` are served from the site root as they are: `public/logo.svg` is `/logo.svg`. Images imported from `src` get a hashed URL: `import logo from './logo.svg'`.
