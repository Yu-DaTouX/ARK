# -*- coding: utf-8 -*-
"""从维基百科取精选条目（CC BY-SA 4.0）到 F:/ARK/data/src/Wikipedia/<标题>.html，给 extract_chunks.py 切段。
去掉历史、词源、分类学、流行文化等与动手无关的章节（参考文献类由 extract_chunks 自己跳过）。
  python fetch_wiki.py
"""
import os, re, time, urllib.error, urllib.parse, urllib.request

D = os.path.join(os.path.dirname(os.path.abspath(__file__)), "Wikipedia")
UA = {"User-Agent": "ArkOffline/0.1 (open-source offline survival app; pack builder)"}
TITLES = {
    "澳洲毒物与危险动物": ["Redback spider", "Sydney funnel-web spider", "Atracidae", "Chironex fleckeri", "Irukandji jellyfish",
                    "Blue-ringed octopus", "Synanceia verrucosa", "Eastern brown snake", "Inland taipan", "Coastal taipan",
                    "Tiger snake", "Common death adder", "Mulga snake", "Red-bellied black snake", "Saltwater crocodile",
                    "Ixodes holocyclus", "Dendrocnide moroides", "Conus geographus", "Portuguese man o' war", "Dingo",
                    "Southern cassowary", "Bull shark", "Shark attack"],
    "澳洲环境与野生食物": ["Bushfires in Australia", "Bush tucker", "Witchetty grub", "Santalum acuminatum", "Tetragonia tetragonioides",
                    "Araucaria bidwillii", "Carpobrotus glaucescens", "Citrus australasica", "Typha", "Marsilea drummondii",
                    "Macrozamia", "Gnamma", "Bogong moth", "Honeypot ant", "Wattleseed"],
    "无线电与求救": ["Q code", "NATO phonetic alphabet", "Morse code", "Prosigns for Morse code", "Dipole antenna", "Monopole antenna",
               "Antenna tuner", "Standing wave ratio", "Skywave", "Near vertical incidence skywave", "Ground wave",
               "Amateur radio emergency communications", "Amateur radio", "Citizens band radio", "UHF CB", "Marine VHF radio",
               "Emergency position-indicating radiobeacon", "Personal locator beacon", "Mayday", "Pan-pan", "Distress signal",
               "Crystal radio", "Amateur radio repeater", "Single-sideband modulation", "Coaxial cable", "Balun",
               "Yagi–Uda antenna", "Satellite phone", "Shortwave radio"],
    "汽车与电力": ["Lead–acid battery", "Lithium-ion battery", "Jump start (vehicle)", "Alternator (automotive)", "Starter (engine)",
              "Spark plug", "Diesel engine", "Two-stroke engine", "Four-stroke engine", "Engine-generator", "Solar panel",
              "Charge controller", "Power inverter", "Multimeter", "Fuse (electrical)", "Siphon", "Flat tire", "Spare tire",
              "Lug nut", "Antifreeze", "Motor oil", "Glow plug", "Carburetor"],
}
SKIP = re.compile(r"^(history|etymology|taxonomy|evolution|phylogeny|in popular culture|popular culture|culture|in culture|cultural|"
                  r"society and culture|gallery|legacy|notable|media|literature|bibliography|in fiction|names?|naming|"
                  r"etymology and naming|systematics|fossil|research|economics|market|manufacturers?|patents?|lawsuits?)", re.I)


def strip_sections(h):
    """去掉标题匹配 SKIP 的整个 <section>（Parsoid HTML 里每个 h2/h3 都包在 section 里）。"""
    out, i = [], 0
    for m in re.finditer(r"<section[^>]*>\s*<h([23])[^>]*>(.*?)</h\1>", h, re.S):
        if m.start() < i: continue
        name = re.sub(r"<[^>]+>", "", m.group(2)).strip()
        if not SKIP.match(name): continue
        depth, j = 0, m.start()
        for t in re.finditer(r"<(/?)section\b", h[m.start():]):
            depth += -1 if t.group(1) else 1
            if depth == 0:
                j = m.start() + t.end(); break
        out.append(h[i:m.start()]); i = h.index(">", j - 1) + 1 if j > m.start() else len(h)
    out.append(h[i:])
    return "".join(out)


def main():
    os.makedirs(D, exist_ok=True)
    miss = []
    for group, ts in TITLES.items():
        for t in ts:
            dst = os.path.join(D, re.sub(r'[\\/:*?"<>|]', "_", t) + ".html")
            if os.path.exists(dst): continue
            url = "https://en.wikipedia.org/api/rest_v1/page/html/" + urllib.parse.quote(t.replace(" ", "_"), safe="")
            h = None
            for k in range(6):  # 维基百科限流：429 时按 Retry-After 退避
                try:
                    h = urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=60).read().decode("utf-8"); break
                except urllib.error.HTTPError as e:
                    if e.code != 429: miss.append((t, str(e)[:60])); break
                    time.sleep(int(e.headers.get("Retry-After") or 0) or 10 * 2 ** k)
                except Exception as e:
                    miss.append((t, str(e)[:60])); break
            if h is None:
                if not miss or miss[-1][0] != t: miss.append((t, "429"))
                continue
            h = strip_sections(h)
            if "<title>" not in h:
                h = f"<title>{t}</title>" + h
            open(dst, "w", encoding="utf-8", newline="\n").write(h)
            time.sleep(2)
    print("取到", len(os.listdir(D)), "篇；没取到：", miss)


if __name__ == "__main__":
    main()
