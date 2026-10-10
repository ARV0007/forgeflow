-- What a project is built with. STATIC: HTML, CSS and vanilla JS, opened as
-- is. REACT: a Vite + React project (package.json, index.html, src/*.jsx) that
-- runs in the browser - compiled there for the preview, or under real Node in
-- a WebContainer. Chosen when the project is created; it decides the agent's
-- instructions, the build check and how the preview runs.
ALTER TABLE projects ADD COLUMN stack VARCHAR(20) NOT NULL DEFAULT 'STATIC'
    CHECK (stack IN ('STATIC', 'REACT'));
