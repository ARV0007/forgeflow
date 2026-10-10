package com.forgeflow.workspace;

/**
 * What a project is built with. It decides three things: the agent's
 * instructions, what the build check looks for, and how the preview runs.
 */
public enum Stack {
    /** HTML, CSS and vanilla JavaScript. The browser opens index.html as is. */
    STATIC,
    /**
     * A Vite + React project: package.json, index.html, src/*.jsx. The preview
     * compiles it in the browser; "Run with Node" boots it under real Node in
     * a WebContainer; the zip runs with npm install && npm run dev.
     */
    REACT
}
