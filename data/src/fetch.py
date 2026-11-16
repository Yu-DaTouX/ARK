# -*- coding: utf-8 -*-
"""下载核心资料包 v4 新增的资料到 F:/ARK/data/src/<书名>/（每本书一个目录，目录名就是书名，extract_chunks.py 直接用）。
  archive.org：优先带文字层的 PDF（Text PDF / Additional Text PDF）；古登堡：HTML 版（章节标题是 h2/h3）；其他：直链。
  python fetch.py
"""
import json, os, re, urllib.request

D = os.path.dirname(os.path.abspath(__file__))
UA = {"User-Agent": "Mozilla/5.0 ark-offline"}

ARCHIVE = {  # 书名: archive.org 条目（全部为美国政府出版物 = 公有领域，或 Hesperian 免费非营利分发）
    "NEETS Module 1 - Matter, Energy, and Direct Current (US Navy)": "NeetsModule1introductionToMatterEnergyAndDirectCurrent",
    "NEETS Module 2 - Alternating Current and Transformers (US Navy)": "NeetsModule2introductionToAlternatingCurrentAndTransformers",
    "NEETS Module 5 - Generators and Motors (US Navy)": "NeetsModule5introductionToGeneratorsAndMotors",
    "NEETS Module 10 - Wave Propagation, Transmission Lines, and Antennas (US Navy)": "NEETSModule10",
    "NEETS Module 17 - Radio-Frequency Communications Principles (US Navy)": "NEETSModule17",
    "FM 24-18 Tactical Single-Channel Radio Communications Techniques (US Army)": "milmanual-fm-24-18-tactical-single-channel-radio-communications-techni",
    "TM 9-8000 Principles of Automotive Vehicles (US Army)": "tm-9-8000-principles-of-automotive-vehicles-1985",
    "Construction Mechanic 3 & 2 (US Navy)": "ERIC_ED170540",
    "ST 31-91B Special Forces Medical Handbook (US Army)": "ST_31-91B_Special_Forces_Medical_Handbook",
    "FM 3-25.26 Map Reading and Land Navigation (US Army)": "fm-3-25.26-map-reading-and-land-navigation",
    "A Community Guide to Environmental Health (Hesperian)": "A_Community_Guide_to_Environmental_Health",
    "Where Women Have No Doctor (Hesperian)": "WhereWomenHaveNoDoctor",
    "Freshwater Fish Pond Culture and Management (Peace Corps)": "ERIC_ED242565",
}
GUTENBERG = {  # 书名: 古登堡编号（公有领域）
    "Deadfalls and Snares (A. R. Harding, 1907)": 34110,
    "Steel Traps (A. R. Harding, 1907)": 34229,
    "Woodcraft and Camping (Nessmuk, 1920)": 34607,
    "Camp Life in the Woods and the Tricks of Trapping (W. H. Gibson, 1881)": 17093,
    "Shelters, Shacks and Shanties (D. C. Beard, 1914)": 28255,
}
DIRECT = {
    "Are You Ready - An In-depth Guide to Citizen Preparedness (FEMA)": "https://www.ready.gov/sites/default/files/2021-11/are-you-ready-guide.pdf",
}


def get(url, dst):
    if os.path.exists(dst) and os.path.getsize(dst) > 0:
        return
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=300) as r, open(dst + ".part", "wb") as f:
        while b := r.read(1 << 20):
            f.write(b)
    os.replace(dst + ".part", dst)


def main():
    for book, ident in ARCHIVE.items():
        m = json.load(urllib.request.urlopen(f"https://archive.org/metadata/{ident}", timeout=60))
        pdfs = [f for f in m["files"] if f["name"].lower().endswith(".pdf")]
        rank = {"Text PDF": 0, "Additional Text PDF": 1}
        f = min(pdfs, key=lambda f: (rank.get(f.get("format"), 2), -int(f.get("size", 0))))
        dst = os.path.join(D, book, "book.pdf")
        get(f"https://archive.org/download/{ident}/{urllib.request.quote(f['name'])}", dst)
        print(f"{os.path.getsize(dst) / 1e6:6.1f} MB  {book}  ← {f['name']} ({f.get('format')})  {m['metadata'].get('title', '')[:50]}")
    for book, n in GUTENBERG.items():
        dst = os.path.join(D, book, "book.html")
        get(f"https://www.gutenberg.org/cache/epub/{n}/pg{n}-images.html", dst)
        print(f"{os.path.getsize(dst) / 1e6:6.1f} MB  {book}")
    for book, url in DIRECT.items():
        dst = os.path.join(D, book, "book.pdf")
        get(url, dst)
        print(f"{os.path.getsize(dst) / 1e6:6.1f} MB  {book}")


if __name__ == "__main__":
    main()
