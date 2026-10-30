import sys, json, pathlib
from playwright.sync_api import sync_playwright

URL = pathlib.Path(sys.argv[1]).resolve().as_uri()
errors, fails = [], []
def check(name, cond, extra=""):
    print(("  ok   " if cond else "  FAIL ") + name + (" " + str(extra) if extra and not cond else ""))
    if not cond: fails.append(name)

with sync_playwright() as p:
    b = p.chromium.launch()
    ctx = b.new_context(viewport={"width": 390, "height": 760}, device_scale_factor=2, has_touch=True)
    pg = ctx.new_page()
    pg.on("console", lambda m: errors.append(m.text) if m.type == "error" else None)
    pg.on("pageerror", lambda e: errors.append(str(e)))
    pg.goto(URL); pg.wait_for_timeout(500)
    pg.screenshot(path="shots/01_start.png")

    check("page title", pg.title() == "Gridbloom")
    check("canvas fills the area", pg.evaluate("document.getElementById('stage').getBoundingClientRect().height") > 300)
    L = pg.evaluate("window.__gb.layout()")
    check("board fits width", L["bx"] >= 0 and L["bx"] + L["bsize"] <= 390, L)
    check("tray below board", L["slots"][0]["y"] >= L["by"] + L["bsize"], L)
    check("3 pieces in tray", pg.evaluate("window.__gb.game().tray.filter(Boolean).length") == 3)

    # real pointer drag with the mouse: slot 0 -> board centre
    def drag_slot(i, tx, ty, steps=12):
        L = pg.evaluate("window.__gb.layout()")
        s = L["slots"][i]
        box = pg.evaluate("(()=>{const r=document.getElementById('stage').getBoundingClientRect();return [r.left,r.top]})()")
        sx, sy = box[0] + s["cx"], box[1] + s["cy"]
        pg.mouse.move(sx, sy); pg.mouse.down()
        pg.mouse.move(box[0] + tx, box[1] + ty, steps=steps)
        pg.screenshot(path="shots/02_dragging.png")
        pg.mouse.up(); pg.wait_for_timeout(120)

    before = pg.evaluate("window.__gb.game().score")
    L = pg.evaluate("window.__gb.layout()")
    drag_slot(0, L["bx"] + L["bsize"] * 0.5, L["by"] + L["bsize"] * 0.5)
    after = pg.evaluate("window.__gb.game().score")
    check("dragging a piece onto the board scores", after > before, (before, after))
    check("piece left the tray", pg.evaluate("window.__gb.game().tray[0]") is None)
    check("board has cells", pg.evaluate("Array.from(window.__gb.game().board).some(v=>v>0)"))

    # drop outside the board: no change
    s0 = pg.evaluate("window.__gb.game().serialize()")
    drag_slot(1, 5, 5)
    check("dropping off the board changes nothing", pg.evaluate("window.__gb.game().serialize()") == s0)

    # touch drag via CDP touch events
    cdp = ctx.new_cdp_session(pg)
    box = pg.evaluate("(()=>{const r=document.getElementById('stage').getBoundingClientRect();return [r.left,r.top]})()")
    L = pg.evaluate("window.__gb.layout()")
    s = L["slots"][1]
    sx, sy = box[0] + s["cx"], box[1] + s["cy"]
    tx, ty = box[0] + L["bx"] + L["cell"] * 1.5, box[1] + L["by"] + L["cell"] * 6.5
    sc0 = pg.evaluate("window.__gb.game().score")
    cdp.send("Input.dispatchTouchEvent", {"type": "touchStart", "touchPoints": [{"x": sx, "y": sy}]})
    for k in range(1, 11):
        cdp.send("Input.dispatchTouchEvent", {"type": "touchMove", "touchPoints": [{"x": sx + (tx - sx) * k / 10, "y": sy + (ty - sy) * k / 10}]})
    cdp.send("Input.dispatchTouchEvent", {"type": "touchEnd", "touchPoints": []})
    pg.wait_for_timeout(150)
    check("touch drag places a piece (lifted above finger)", pg.evaluate("window.__gb.game().score") > sc0)

    # force a clear with the engine and make sure effects + score run without errors
    pg.evaluate("""(()=>{const g=window.__gb.game(); g.board.fill(0); for(let c=0;c<7;c++) g.board[c]=3; g.board[40]=2; g.tray=['dot','dot','dot'];})()""")
    pg.evaluate("window.__gb.place(0,0,7)")
    pg.wait_for_timeout(120)
    pg.screenshot(path="shots/03_clear.png")
    check("line clear empties the row", pg.evaluate("Array.from(window.__gb.game().board).slice(0,8).every(v=>v===0)"))
    pg.wait_for_timeout(1200)
    check("score shown on screen matches", pg.inner_text("#score") == str(pg.evaluate("window.__gb.game().score")))

    # reload resumes the saved game
    sc = pg.evaluate("window.__gb.game().score")
    pg.reload(); pg.wait_for_timeout(500)
    check("reload resumes the unfinished game", pg.evaluate("window.__gb.game().score") == sc and sc > 0, (sc,))

    # game over
    pg.evaluate("""(()=>{const g=window.__gb.game(); for(let r=0;r<8;r++)for(let c=0;c<8;c++) g.board[r*8+c]=((r+c)%2===0)?1:0; g.tray=['dot','sq3',null]; g.streak=0; g.since=0;})()""")
    pg.evaluate("window.__gb.place(0,0,1)")
    pg.wait_for_timeout(1300)
    check("game over dialog appears", pg.is_visible("#over"))
    check("rescue offered once", pg.is_visible("#rescue"))
    pg.screenshot(path="shots/04_gameover.png")
    pg.click("#rescue"); pg.wait_for_timeout(200)
    check("rescue closes dialog and game continues", (not pg.is_visible("#over")) and pg.evaluate("!window.__gb.game().over"))
    pg.evaluate("""(()=>{const g=window.__gb.game(); for(let r=0;r<8;r++)for(let c=0;c<8;c++) g.board[r*8+c]=((r+c)%2===0)?1:0; g.tray=['dot','sq3',null]; g.streak=0;g.since=0;})()""")
    pg.evaluate("window.__gb.place(0,0,1)"); pg.wait_for_timeout(1000)
    check("second game over has no rescue", pg.is_visible("#over") and not pg.is_visible("#rescue"))
    pg.click("#again"); pg.wait_for_timeout(300)
    check("play again starts fresh", pg.evaluate("window.__gb.game().score") == 0 and not pg.is_visible("#over"))

    # settings
    pg.click("#menu"); pg.wait_for_timeout(150)
    check("settings open", pg.is_visible("#settings"))
    pg.screenshot(path="shots/05_settings.png")
    pg.click("#optSound")
    check("sound toggle flips", pg.get_attribute("#optSound", "aria-checked") == "false")
    pg.click("#skins button:nth-child(3)"); pg.wait_for_timeout(100)
    pg.click("#closeSet"); pg.wait_for_timeout(200)
    pg.screenshot(path="shots/06_mint.png")
    pg.reload(); pg.wait_for_timeout(400)
    st = json.loads(pg.evaluate("localStorage.getItem('gb.settings')"))
    check("settings persist", st["skin"] == "mint" and st["sound"] is False, st)

    # blocked storage must not break the game
    ctx2 = b.new_context(viewport={"width": 390, "height": 760})
    pg2 = ctx2.new_page(); e2 = []
    pg2.on("pageerror", lambda e: e2.append(str(e)))
    pg2.add_init_script("Object.defineProperty(window,'localStorage',{get(){throw new Error('blocked')}})")
    pg2.goto(URL); pg2.wait_for_timeout(400)
    check("works with storage blocked", pg2.evaluate("window.__gb.game().tray.filter(Boolean).length") == 3 and not e2, e2)

    # desktop size + small phone
    for w, h, name in [(1280, 800, "desktop"), (320, 568, "small"), (844, 390, "landscape")]:
        c3 = b.new_context(viewport={"width": w, "height": h}); p3 = c3.new_page()
        p3.goto(URL); p3.wait_for_timeout(400); p3.screenshot(path=f"shots/07_{name}.png")
        L3 = p3.evaluate("window.__gb.layout()")
        last = L3["slots"][2]; ok = L3["bx"] >= 0 and L3["bx"] + L3["bsize"] <= w and last["y"] + last["h"] <= h + 2 and last["x"] + last["w"] <= w + 2 and L3["by"] >= 0 and L3["cell"] >= 18
        check(f"layout fits {name} {w}x{h}", ok, L3["cell"])
        c3.close()

    check("no console or page errors", not errors, errors)
    b.close()

print("\nFAILED:" if fails else "\nALL UI CHECKS PASSED", fails if fails else "")
sys.exit(1 if fails else 0)
