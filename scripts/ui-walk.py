"""Drive the ForgeFlow workbench end to end in headless Chromium.

Run against a local server started in demo mode, so no API key or payment
account is needed and every step is deterministic:

    FORGEFLOW_LLM_PROVIDER=demo FORGEFLOW_BILLING_PROVIDER=fake \
    FORGEFLOW_EMBEDDER=hashing FORGEFLOW_SANDBOX_PROVIDER=in-process \
    ./mvnw spring-boot:run

    pip install playwright && playwright install chromium
    python3 scripts/ui-walk.py            # screenshots land in ./ui-walk-shots/

Signs up, chats, opens the code, starts a preview, clicks inside the generated
app and checks its console line arrives in Logs, searches, opens the version
history and restores a version, lets the AI check how its app looks (and fix
it once), sends a screenshot, builds a React project that compiles and runs in
the browser (and opens it under Node in a WebContainer), downloads the zip,
upgrades through the test checkout, shares with a viewer and checks the
viewer is read-only, hits the project quota, and checks a phone-sized screen.
"""
import os, re, sys, uuid
from playwright.sync_api import sync_playwright, expect

BASE = os.environ.get("FORGEFLOW_URL", "http://localhost:8081")
import os
OUT = os.environ.get("UI_WALK_OUT", "ui-walk-shots")
os.makedirs(OUT, exist_ok=True)
errors = []

def shot(page, name):
    page.screenshot(path=f"{OUT}/{name}.png", full_page=False)

with sync_playwright() as p:
    browser = p.chromium.launch()
    page = browser.new_page(viewport={"width": 1440, "height": 900})
    # GET /preview answers 404 when nothing is running - the documented contract,
    # which the browser logs as a failed resource. Anything else is a real error.
    expected_404 = []
    page.on("response", lambda r: expected_404.append(r.url) if r.status == 404 and r.url.endswith("/preview") else None)
    # Step 10c breaks a React component on purpose; its compile error is expected.
    page.on("console", lambda m: errors.append(f"console.{m.type}: {m.text}")
            if m.type == "error" and "status of 404" not in m.text
            and not m.text.startswith("Build error: src/components/Counter.jsx") else None)
    # Step 5b throws inside the generated app on purpose; anything else is real.
    page.on("pageerror", lambda e: errors.append(f"pageerror: {e}") if "total is undefined" not in str(e) else None)

    # 1. sign up
    page.goto(BASE + "/")
    email = f"ui-{uuid.uuid4().hex[:8]}@test.dev"
    page.fill("#email", email)
    page.fill("#password", "correct-horse-1")
    page.click("#btn-signup")
    expect(page.locator("#shell")).to_be_visible(timeout=10000)
    expect(page.locator("#gate")).to_be_hidden()
    expect(page.locator("#project-select option")).to_have_count(1)
    expect(page.locator("#btn-plan")).to_contain_text("Free")
    expect(page.locator("#vcheck-toggle")).to_be_checked()          # on by default
    page.uncheck("#vcheck-toggle")                                   # off until step 5c, so counts below stay simple
    shot(page, "01-empty-workbench")

    # 2. chat: send a message, watch the live reply, see the saved reply
    page.fill("#prompt", "A tip calculator for a cafe")
    page.click("#btn-send")
    expect(page.locator(".msg-user").last).to_have_text("A tip calculator for a cafe")
    expect(page.locator(".msg-bot:not(.is-working)").last).to_contain_text("Built a starter page", timeout=20000)
    expect(page.locator(".file-chip")).to_have_count(3)
    expect(page.locator(".session")).to_have_count(1)
    expect(page.locator(".session.is-on")).to_contain_text("A tip calculator for a cafe")
    shot(page, "02-after-first-reply")

    # 3. memory survives a reload
    page.reload()
    expect(page.locator(".msg-user")).to_have_count(1, timeout=10000)
    expect(page.locator(".msg-bot")).to_have_count(1)

    # 4. code tab via a file chip, line numbers rendered
    page.locator(".file-chip", has_text="index.html").click()
    expect(page.locator("#tab-code")).to_be_visible()
    expect(page.locator("#code-name")).to_have_text("index.html")
    expect(page.locator("#code-body .ln").first).to_be_visible()
    expect(page.locator(".file")).to_have_count(3)
    shot(page, "03-code")

    # 5. preview: start, iframe loads the generated app, its console reaches Logs
    page.click(".tab[data-tab=preview]")
    page.click("#btn-preview")
    expect(page.locator("#preview-frame")).to_be_visible(timeout=10000)
    expect(page.locator("#preview-status")).to_contain_text("Live")
    frame = page.frame_locator("#preview-frame")
    expect(frame.locator("h1")).to_have_text("A tip calculator for a cafe", timeout=10000)
    frame.locator("#count").click()
    expect(frame.locator("#count")).to_have_text("Clicked 1 time")
    shot(page, "04-preview")

    page.click(".tab[data-tab=logs]")
    expect(page.locator("#log-conn")).to_have_text("live", timeout=10000)
    expect(page.locator(".log", has_text="Build passed").first).to_be_visible()
    expect(page.locator(".log", has_text="Preview started").first).to_be_visible()
    expect(page.locator(".log", has_text="GET /index.html 200").first).to_be_visible()
    expect(page.locator(".log .src-console").first).to_be_visible(timeout=10000)   # console.log from the click
    expect(page.locator(".log", has_text="button clicked 1").first).to_be_visible()
    shot(page, "05-logs")

    # 5b. the runtime loop: an error thrown INSIDE the generated app travels
    #     bridge -> logs -> the bar above the composer -> "fix it" -> the agent
    app_frame = next(f for f in page.frames if "/p/" in f.url)
    app_frame.evaluate("setTimeout(() => { throw new TypeError('total is undefined') }, 0)")
    expect(page.locator("#runtime-bar")).to_be_visible(timeout=10000)
    expect(page.locator("#runtime-text")).to_contain_text("total is undefined")
    shot(page, "05b-runtime-error")
    page.click("#btn-fix")
    expect(page.locator(".msg-user").last).to_have_text("Fix the error the preview reported.")
    expect(page.locator(".msg-bot:not(.is-working)")).to_have_count(2, timeout=20000)
    expect(page.locator("#runtime-bar")).to_be_hidden(timeout=10000)   # the run's build cleared it

    # 5c. AI checks its own app: the preview is photographed in the browser,
    #     a vision model (the demo stand-in here) flags a major issue, ONE fix
    #     turn goes back to the agent on its own, and the re-check passes.
    page.check("#vcheck-toggle")
    page.fill("#prompt", "Make the counter button stand out")
    page.click("#btn-send")
    expect(page.locator(".vcheck-bad")).to_have_count(1, timeout=40000)
    expect(page.locator(".vcheck-bad .vcheck-score")).to_have_text("6/10")
    expect(page.locator(".vcheck-bad .vi-major")).to_contain_text("counter button")
    page.wait_for_function("() => { const i = document.querySelector('.vcheck-bad .vcheck-shot');"
                           " return i && i.naturalWidth > 200; }", timeout=10000)     # a real picture of the page
    expect(page.locator(".msg-user").last).to_contain_text("Visual check (6/10) found problems", timeout=20000)
    expect(page.locator(".vcheck-ok")).to_have_count(1, timeout=40000)
    expect(page.locator(".vcheck-ok .vcheck-score")).to_have_text("9/10")
    page.wait_for_timeout(1500)
    expect(page.locator(".msg-bot:not(.is-working)")).to_have_count(4)     # one fix round, not a loop
    shot(page, "05c-visual-check")
    page.uncheck("#vcheck-toggle")
    page.reload()
    expect(page.locator(".vcheck")).to_have_count(2, timeout=10000)         # verdicts stay with their replies
    expect(page.locator(".vcheck-bad .vcheck-note")).to_have_text("Sent back to the agent to fix.")
    expect(page.locator(".vcheck-fix")).to_have_count(0)                    # no button for what was already fixed
    expect(page.locator("#vcheck-toggle")).not_to_be_checked()              # the choice is remembered

    # 6. search
    page.click(".tab[data-tab=search]")
    page.fill("#search-q", "button clicked")
    page.click("#btn-search")
    expect(page.locator(".hit").first).to_be_visible(timeout=10000)
    expect(page.locator(".hit").first.locator("code")).to_have_text("app.js")
    page.locator(".hit").first.click()
    expect(page.locator("#code-name")).to_have_text("app.js")
    expect(page.locator("#code-body .ln.hl").first).to_be_visible()
    shot(page, "06-search")

    # 6b. history: every AI run is a version; its diff renders; restore needs two clicks
    page.click(".tab[data-tab=history]")
    expect(page.locator(".cp").first).to_be_visible(timeout=10000)
    versions = page.locator(".cp").count()
    page.locator(".cp").last.click()                       # the oldest: the first build
    expect(page.locator(".df").first).to_be_visible(timeout=10000)
    expect(page.locator(".df-status").first).to_have_text("ADDED")
    expect(page.locator(".df-body .d-add").first).to_be_visible()
    page.click("#btn-restore")
    expect(page.locator("#btn-restore")).to_have_text("Click again to restore")
    page.click("#btn-restore")
    expect(page.locator(".toast").last).to_contain_text("Restored", timeout=10000)
    expect(page.locator(".cp")).to_have_count(versions + (1 if versions > 1 else 0), timeout=10000)
    if versions > 1:
        expect(page.locator("#cp-title")).to_contain_text("Restored to #")     # the detail follows the new version
    shot(page, "06b-history")

    # 7. download the zip
    with page.expect_download() as dl:
        page.click("#btn-download")
    assert dl.value.suggested_filename == "my-first-app.zip", dl.value.suggested_filename

    # 8. plan dialog: usage, upgrade through the fake checkout
    page.click("#btn-plan")
    expect(page.locator("#dlg-plan")).to_be_visible()
    expect(page.locator(".usage-row")).to_have_count(3)
    expect(page.locator(".plan")).to_have_count(2)
    shot(page, "07-plans")
    page.click(".plan [data-plan=PRO]")
    page.wait_for_url(re.compile(r".*/billing/fake-checkout/.*"), timeout=10000)
    page.click("text=Complete test payment")
    page.wait_for_url(re.compile(r".*/(\?billing=success)?$"), timeout=10000)
    expect(page.locator("#toast")).to_contain_text("Payment received", timeout=10000)
    expect(page.locator("#btn-plan")).to_contain_text("Pro", timeout=10000)
    shot(page, "08-pro")

    # 9. share with a viewer; the viewer sees it read-only
    viewer = f"viewer-{uuid.uuid4().hex[:8]}@test.dev"
    ctx2 = browser.new_context(viewport={"width": 1440, "height": 900})
    page2 = ctx2.new_page()
    page2.goto(BASE + "/")
    page2.fill("#email", viewer); page2.fill("#password", "correct-horse-1"); page2.click("#btn-signup")
    expect(page2.locator("#shell")).to_be_visible(timeout=10000)

    page.click("#btn-share")
    expect(page.locator("#dlg-share")).to_be_visible()
    page.fill("#invite-email", viewer)
    page.select_option("#invite-role", "VIEWER")
    page.click("#btn-invite")
    expect(page.locator(".member")).to_have_count(2, timeout=10000)
    shot(page, "09-share")
    page.click("#dlg-share [data-close]")

    page2.reload()
    expect(page2.locator("#project-select option")).to_have_count(2, timeout=10000)
    shared = page2.locator("#project-select option", has_text="viewer").get_attribute("value")
    page2.select_option("#project-select", shared)
    expect(page2.locator("#role-badge")).to_have_text("viewer")
    expect(page2.locator("#prompt")).to_be_disabled()
    expect(page2.locator("#btn-build")).to_be_disabled()
    expect(page2.locator(".msg-bot")).to_have_count(4, timeout=10000)   # can read the chat (build, fix, change, visual fix)
    expect(page2.locator(".vcheck")).to_have_count(2)                     # sees the visual checks...
    expect(page2.locator(".vcheck-fix")).to_have_count(0)                 # ...but can't act on them
    expect(page2.locator("#vcheck-toggle")).to_be_disabled()
    shot(page2, "10-viewer")

    # 10. a 402 surfaces as a friendly toast with a way to upgrade (viewer is on FREE)
    for i in range(3):
        page2.evaluate("""async (i) => { await fetch('/api/v1/projects', {method:'POST',
            headers:{'Content-Type':'application/json', Authorization:'Bearer '+localStorage.getItem('ff_token')},
            body: JSON.stringify({name: 'p'+i})}); }""", i)
    page2.click("#btn-new-project")
    page2.fill("#new-name", "one too many")
    page2.click("#btn-create")
    expect(page2.locator("#new-msg")).to_contain_text("allows 3 projects", timeout=10000)
    expect(page2.locator("#new-msg .linklike")).to_be_visible()
    shot(page2, "11-quota")

    # 10b. screenshot to app: attach an image, it shows in the message, survives a reload
    import struct, zlib, tempfile
    def tiny_png(w=40, h=30):
        raw = b"".join(b"\x00" + bytes([200, 80, 40]) * w for _ in range(h))
        def chunk(t, d):
            return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xffffffff)
        return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
                + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))
    png = tempfile.NamedTemporaryFile(suffix=".png", delete=False)
    png.write(tiny_png()); png.close()
    page.set_input_files("#attach-input", png.name)
    expect(page.locator("#attach-list .attach")).to_have_count(1, timeout=10000)
    page.fill("#prompt", "Build this from my sketch")
    page.click("#btn-send")
    expect(page.locator(".msg-user-wrap .msg-images img")).to_have_count(1, timeout=10000)
    expect(page.locator("#attach-list")).to_be_hidden()
    expect(page.locator(".msg-bot:not(.is-working)")).to_have_count(5, timeout=20000)
    page.reload()
    img = page.locator(".msg-user-wrap .msg-images img").first
    expect(img).to_be_visible(timeout=10000)
    page.wait_for_function("() => { const i = document.querySelector('.msg-images img'); return i && i.naturalWidth === 40; }",
                           timeout=10000)                       # the real bytes, fetched with the token
    shot(page, "10b-screenshot")

    # 10c. React: a Vite + React project, compiled and run in the browser by the preview's runner
    page.click("#btn-new-project")
    page.fill("#new-name", "react app")
    page.select_option("#new-stack", "REACT")
    page.click("#btn-create")
    expect(page.locator("#stack-badge")).to_be_visible(timeout=10000)
    expect(page.locator("#project-select option:checked")).to_have_text("react app")
    page.fill("#prompt", "A habit tracker")
    page.click("#btn-send")
    expect(page.locator(".file-chip", has_text="src/components/Counter.jsx")).to_be_visible(timeout=20000)
    page.click(".tab[data-tab=preview]")
    page.click("#btn-preview")
    rframe = page.frame_locator("#preview-frame")
    expect(rframe.locator("h1")).to_have_text("A habit tracker", timeout=15000)
    rframe.locator("#count").click()
    rframe.locator("#count").click()
    expect(rframe.locator("#count")).to_have_text("Clicked 2 times")          # useState: React is really running
    page.click(".tab[data-tab=logs]")
    expect(page.locator(".log", has_text="react button clicked 2").first).to_be_visible(timeout=10000)
    page.click(".tab[data-tab=preview]")
    shot(page, "10c-react")

    #     the visual check photographs the RENDERED React app, even on a slow link
    #     (regression: on Render the camera fired before the runner had fetched the
    #     sources, and Gemini scored a blank page 1/10)
    cdp = page.context.new_cdp_session(page)
    cdp.send("Network.enable")
    cdp.send("Network.emulateNetworkConditions", {"offline": False, "latency": 500,
                                                 "downloadThroughput": -1, "uploadThroughput": -1})
    page.check("#vcheck-toggle")
    page.fill("#prompt", "Make the habits look nicer")
    page.click("#btn-send")
    expect(page.locator(".vcheck-shot").first).to_be_visible(timeout=60000)
    inked = page.evaluate("""() => { const i = document.querySelector('.vcheck-shot');
        const c = document.createElement('canvas'); c.width = i.naturalWidth; c.height = i.naturalHeight;
        const g = c.getContext('2d'); g.drawImage(i, 0, 0); const d = g.getImageData(0, 0, c.width, c.height).data;
        let n = 0; for (let k = 0; k < d.length; k += 4) if (d[k] + d[k + 1] + d[k + 2] < 600) n++; return n; }""")
    assert inked > 500, f"the visual check photographed a blank page ({inked} dark pixels)"
    expect(page.locator(".vcheck-ok")).to_have_count(1, timeout=60000)      # the one automatic fix round, then done
    page.uncheck("#vcheck-toggle")
    cdp.send("Network.emulateNetworkConditions", {"offline": False, "latency": 0,
                                                 "downloadThroughput": -1, "uploadThroughput": -1})

    #     a compile error in a component lands in the runtime bar (and so in the agent's next turn)
    page.evaluate("""async () => { await fetch('/api/v1/projects/' + document.getElementById('project-select').value + '/files/content', {
        method: 'PUT', headers: {'Content-Type': 'application/json', Authorization: 'Bearer ' + localStorage.getItem('ff_token')},
        body: JSON.stringify({path: 'src/components/Counter.jsx', content: 'export default function Counter() { return <button>oops</button>;'}) }); }""")
    page.evaluate("() => { const f = document.getElementById('preview-frame'); f.src = f.src; }")
    expect(rframe.locator("[data-forgeflow-error]")).to_contain_text("src/components/Counter.jsx", timeout=15000)
    expect(page.locator("#runtime-bar")).to_be_visible(timeout=10000)
    expect(page.locator("#runtime-text")).to_contain_text("Build error")
    shot(page, "10d-react-compile-error")

    #     "Run with Node" opens its own cross-origin-isolated tab for the WebContainer
    with page.context.expect_page() as opened:
        page.click("#btn-node")
    node = opened.value
    node.wait_for_load_state()
    assert node.evaluate("crossOriginIsolated") is True, "run.html is not cross-origin isolated"
    expect(node.locator('[data-step="isolate"]')).to_have_class("is-done", timeout=10000)
    # Booting needs StackBlitz's servers. Offline (CI, this walk) it must end in a
    # readable reason, not a spinner; online it gets to npm install.
    expect(node.locator('.run-error, [data-step="install"].is-active, [data-step="install"].is-done').first
           ).to_be_visible(timeout=60000)
    shot(node, "10e-run-with-node")
    node.close()

    # 11. narrow screen still usable
    page.set_viewport_size({"width": 390, "height": 844})
    page.reload()
    expect(page.locator("#prompt")).to_be_visible(timeout=10000)
    shot(page, "12-mobile")

    browser.close()

print("UI walk passed")
if errors:
    print("BROWSER ERRORS:")
    print("\n".join(errors))
    sys.exit(1)
