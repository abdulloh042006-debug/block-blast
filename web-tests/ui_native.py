import sys, pathlib
from playwright.sync_api import sync_playwright
URL = pathlib.Path(sys.argv[1]).resolve().as_uri()
fails = []
def check(name, cond, extra=""):
    print(("  ok   " if cond else "  FAIL ") + name + (" " + str(extra) if extra and not cond else ""))
    if not cond: fails.append(name)

MOCK = """
window.__calls = [];
window.GridbloomNative = {
  isRewardedReady: function(){ window.__calls.push('ready'); return window.__ready !== false; },
  requestRescue: function(){ window.__calls.push('rescue'); },
  beforeNewGame: function(){ window.__calls.push('beforeNew'); },
  onGameOver: function(){ window.__calls.push('over'); },
  openPrivacy: function(){ window.__calls.push('privacy'); }
};
"""
OVER = """(()=>{const g=window.__gb.game(); for(let r=0;r<8;r++)for(let c=0;c<8;c++) g.board[r*8+c]=((r+c)%2===0)?1:0; g.tray=['dot','sq3',null]; g.streak=0; g.since=0;})()"""

with sync_playwright() as p:
    b = p.chromium.launch(); errs = []
    ctx = b.new_context(viewport={"width": 390, "height": 760}); pg = ctx.new_page()
    pg.on("pageerror", lambda e: errs.append(str(e)))
    pg.add_init_script(MOCK); pg.goto(URL); pg.wait_for_timeout(400)

    pg.evaluate(OVER); pg.evaluate("window.__gb.place(0,0,1)"); pg.wait_for_timeout(1100)
    check("game over dialog shows", pg.is_visible("#over"))
    check("native told about game over", "over" in pg.evaluate("window.__calls"))
    check("rescue button is the ad version", "ad" in pg.inner_text("#rescue").lower())
    pg.click("#rescue"); pg.wait_for_timeout(100)
    check("tapping rescue asks native, does NOT rescue yet", "rescue" in pg.evaluate("window.__calls") and pg.evaluate("window.__gb.game().over"))
    pg.click("#rescue", force=True); pg.wait_for_timeout(50)
    check("double tap does not ask twice", pg.evaluate("window.__calls.filter(x=>x==='rescue').length") == 1)

    pg.evaluate("window.__gbNative.rescueResult(false)"); pg.wait_for_timeout(100)
    check("ad failed: still game over, dialog stays", pg.is_visible("#over") and pg.evaluate("window.__gb.game().over"))
    pg.click("#rescue"); pg.evaluate("window.__gbNative.rescueResult(true)"); pg.wait_for_timeout(200)
    check("reward earned: game continues", (not pg.is_visible("#over")) and pg.evaluate("!window.__gb.game().over"))

    pg.evaluate(OVER.replace("g.tray=['dot','sq3',null]", "window.__gb.game().rescues=1; g.tray=['dot','sq3',null]")); pg.evaluate("window.__gb.place(0,0,1)"); pg.wait_for_timeout(1000)
    check("no rescue button once used", pg.is_visible("#over") and not pg.is_visible("#rescue"))
    pg.click("#again"); pg.wait_for_timeout(100)
    check("play again goes through native (interstitial) first", "beforeNew" in pg.evaluate("window.__calls") and pg.evaluate("window.__gb.game().over"))
    pg.evaluate("window.__gbNative.proceedNewGame()"); pg.wait_for_timeout(200)
    check("native releases the new game", pg.evaluate("window.__gb.game().score") == 0 and not pg.is_visible("#over"))

    pg.click("#menu"); pg.wait_for_timeout(100)
    check("privacy button visible with native", pg.is_visible("#privacy"))
    pg.click("#privacy"); check("privacy goes to native", "privacy" in pg.evaluate("window.__calls"))

    # no ad ready -> no rescue offered
    pg.click("#closeSet"); pg.evaluate("window.__ready=false")
    pg.evaluate(OVER.replace("g.tray=['dot','sq3',null]", "window.__gb.game().rescues=0; g.tray=['dot','sq3',null]")); pg.evaluate("window.__gb.place(0,0,1)"); pg.wait_for_timeout(1000)
    check("no ad ready: rescue hidden", pg.is_visible("#over") and not pg.is_visible("#rescue"))
    check("no page errors", not errs, errs)

    # plain browser: no native -> privacy hidden, free rescue
    pg2 = b.new_context(viewport={"width": 390, "height": 760}).new_page(); pg2.goto(URL); pg2.wait_for_timeout(300)
    pg2.click("#menu"); check("web: privacy hidden", not pg2.is_visible("#privacy"))
    b.close()
print("\nFAILED:" if fails else "\nNATIVE BRIDGE CHECKS PASSED", fails if fails else "")
sys.exit(1 if fails else 0)
