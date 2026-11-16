# -*- coding: utf-8 -*-
"""离线资料库下载流水线：先下到 F 盘固态暂存区，处理好，再一个一个顺序写进 G 盘机械盘。

  python inventory.py   # 先生成 F:/OfflineStage/inventory.json
  python pull.py        # 可随时中断，重跑会接着做（aria2 断点续传；G 盘已有的跳过）

三条线同时跑：
  下载：aria2 多连接下到 F:/OfflineStage/dl（固态随机写不怕），按批进行，校验大小 / SHA-256。
        暂存区（下载中 + 待搬）不超过 ARK_CAP_GB（默认 200）；F 盘至少留 ARK_RESERVE_GB（默认 50）。
        单个文件比上限还大（古登堡 221 GB）时，等暂存区清空后单独下。
  处理：全球 OsmAnd 地图 zip → 解出 .obf → xz -9e（ARK_XZ 个进程并行）；澳洲和概览地图解压成 .obf；其余原样。
  搬运：单线程顺序写 G 盘：先按大小预留空间（减少碎片）→ 16 MB 大块顺序写 → 读回比对 SHA-1 → 删掉固态上的那份。
        每个文件写进 G:/Offline/_lists/manifest.tsv（路径、大小、SHA-1、时间）。
进度：F:/OfflineStage/status.txt（每分钟更新），日志 F:/OfflineStage/pull.log。
"""
import hashlib, json, lzma, os, queue, shutil, subprocess, threading, time, zipfile
import concurrent.futures as cf

STAGE = "F:/OfflineStage"
DL, READY = f"{STAGE}/dl", f"{STAGE}/ready"
G = "G:/Offline"
CAP = float(os.environ.get("ARK_CAP_GB", "200")) * 1e9
RESERVE = float(os.environ.get("ARK_RESERVE_GB", "50")) * 1e9
BATCH = 10e9            # 一批的大小；超过它的大文件单独一批
SLOTS = int(os.environ.get("ARK_SLOTS", "3"))  # 同时跑几批（大文件在下时不挡着小文件）
XZ = int(os.environ.get("ARK_XZ", "8"))
BUF = 16 << 20
MANIFEST = f"{G}/_lists/manifest.tsv"

lock = threading.Lock()
inflight = 0.0          # 正在下载的批次预留的字节
state = dict(moving="", moved=0, moved_bytes=0, downloaded=0, failed=[], dl_batch="", t0=time.time(),
             phase="准备中", src_done=0, n0=0, b0=0, n_total=0, b_total=0)  # n0/b0：本次启动前 G 盘已有的
proc_q, move_q = queue.Queue(), queue.Queue()
pending_proc = 0        # 已下完、还没处理完的个数
plock = threading.Lock()


def pend(d):
    global pending_proc
    with plock:
        pending_proc += d


def log(*a):
    s = time.strftime("[%m-%d %H:%M:%S] ") + " ".join(map(str, a))
    print(s, flush=True)
    with lock:
        open(f"{STAGE}/pull.log", "a", encoding="utf-8").write(s + "\n")


def du(path):
    n = 0
    for r, _, fs in os.walk(path):
        for f in fs:
            try: n += os.path.getsize(os.path.join(r, f))
            except OSError: pass
    return n


def free(path):
    return shutil.disk_usage(path).free


def gpath(it):
    return f"{G}/{it['cat']}/{it['final']}"


# ---------- 搬运：顺序写 G 盘 ----------
def copy_verify(src, dst):
    size = os.path.getsize(src)
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    tmp = dst + ".part"
    h1 = hashlib.sha1()
    with open(src, "rb", buffering=0) as fi, open(tmp, "wb", buffering=0) as fo:
        fo.truncate(size); fo.seek(0)          # 一次性按大小分配，NTFS 尽量给连续空间
        while True:
            b = fi.read(BUF)
            if not b: break
            h1.update(b); fo.write(b)
        os.fsync(fo.fileno())
    h2 = hashlib.sha1()
    with open(tmp, "rb", buffering=0) as f:
        while True:
            b = f.read(BUF)
            if not b: break
            h2.update(b)
    if h1.digest() != h2.digest():
        os.remove(tmp)
        raise IOError(f"读回校验不一致：{dst}")
    os.replace(tmp, dst)
    return size, h1.hexdigest()


def mover():
    while True:
        it, src, keep = move_q.get()
        dst = gpath(it)
        state["moving"] = dst
        t = time.time()
        for attempt in (1, 2):
            try:
                size, sha = copy_verify(src, dst)
                break
            except Exception as e:
                log("搬运失败", attempt, dst, e)
                if attempt == 2:
                    state["failed"].append(dst); size = 0
        if size:
            if not keep: os.remove(src)
            with lock:
                open(MANIFEST, "a", encoding="utf-8").write(f"{it['cat']}/{it['final']}\t{size}\t{sha}\t{time.strftime('%F %T')}\n")
            state["moved"] += 1; state["moved_bytes"] += size; state["src_done"] += it["size"]
            if size > 2e8:
                log(f"写入 G 盘 {dst}  {size / 1e9:.2f} GB  {size / 1e6 / max(1e-3, time.time() - t):.0f} MB/s（含读回校验）")
        state["moving"] = ""
        move_q.task_done()


# ---------- 处理 ----------
def xz_one(src, dst):
    """zip 里的 .obf → xz -9e，解压校验长度后返回。在子进程里跑。"""
    with zipfile.ZipFile(src) as z:
        m = next(i for i in z.infolist() if i.filename.endswith(".obf"))
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        with z.open(m) as fi, lzma.open(dst + ".part", "wb", preset=9 | lzma.PRESET_EXTREME) as fo:
            shutil.copyfileobj(fi, fo, BUF)
    n = 0
    with lzma.open(dst + ".part", "rb") as f:
        while True:
            b = f.read(BUF)
            if not b: break
            n += len(b)
    if n != m.file_size:
        raise IOError(f"xz 校验失败 {src}")
    os.replace(dst + ".part", dst)
    os.remove(src)
    return dst


def processor(pool):
    while True:
        it, src = proc_q.get()
        out = f"{READY}/{it['cat']}/{it['final']}"
        os.makedirs(os.path.dirname(out), exist_ok=True)
        try:
            if it["post"] == "none":
                os.replace(src, out); move_q.put((it, out, False)); pend(-1)
            elif it["post"] == "unzip":
                with zipfile.ZipFile(src) as z:
                    m = next(i for i in z.infolist() if i.filename.endswith(".obf"))
                    with z.open(m) as fi, open(out + ".part", "wb") as fo:
                        shutil.copyfileobj(fi, fo, BUF)
                os.replace(out + ".part", out); os.remove(src)
                move_q.put((it, out, False)); pend(-1)
            else:
                def done(f, it=it, out=out):
                    try:
                        f.result(); move_q.put((it, out, False))
                    except Exception as e:
                        log("压缩失败", it["name"], e); state["failed"].append(it["name"])
                    pend(-1)
                pool.submit(xz_one, src, out).add_done_callback(done)
        except Exception as e:
            log("处理失败", it["name"], e); state["failed"].append(it["name"]); pend(-1)


# ---------- 下载 ----------
def staged():
    return du(DL) + du(READY)


def aria2(batch, tag, slot):
    inp = f"{STAGE}/batch_{tag}.txt"
    with open(inp, "w", encoding="utf-8", newline="\n") as f:
        for it in batch:
            f.write(it["url"] + f"\n  dir={DL}/{it['cat']}\n")
            if not it["url"].endswith(".meta4"):
                f.write(f"  out={it['name']}\n")
    cmd = ["aria2c", "--disable-ipv6=true", "--continue=true", "--max-concurrent-downloads=5", "--split=4",
           "--max-connection-per-server=2", "--min-split-size=20M", "--follow-metalink=mem", "--check-integrity=true",
           "--auto-file-renaming=false", "--allow-overwrite=false", "--file-allocation=none", "--max-tries=10",
           "--retry-wait=15", "--timeout=60", "--console-log-level=error", "--summary-interval=0",
           "--download-result=hide", f"--log={STAGE}/aria2_{slot}.log", "--log-level=notice", f"--input-file={inp}"]
    subprocess.run(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))


active = {}  # slot → 这一批的说明


def downloader(todo):
    """按清单顺序组批，最多 SLOTS 批同时下（各自一个 aria2）。"""
    global inflight
    tries, threads = {}, []
    while todo or any(t.is_alive() for t in threads):
        free_slots = [s for s in range(SLOTS) if s not in active]
        if not todo or not free_slots:
            time.sleep(5); continue
        # 组一批：总大小不超过 BATCH 和当前可用额度
        st = staged()
        room = min(CAP - st - pending_bytes(), free("F:/") - RESERVE)
        first = todo[0]
        try:  # 已经下了一部分的（断点续传），只算还没下的那部分
            got = os.path.getsize(f"{DL}/{first['cat']}/{first['name']}")
        except OSError:
            got = 0
        if first["size"] - got > room:
            if pending_bytes() == 0 and first["size"] - got <= free("F:/") - RESERVE:
                room = first["size"]       # 超大单个文件：暂存区空了就单独下
            else:
                time.sleep(30); continue
        batch, n = [], 0
        if first["size"] >= BATCH:
            batch, n = [todo.pop(0)], first["size"]
        else:
            while todo and len(batch) < 400 and todo[0]["size"] < BATCH and n + todo[0]["size"] <= min(room, BATCH):
                it = todo.pop(0); batch.append(it); n += it["size"]
        slot = free_slots[0]
        with lock:
            inflight += n - got  # 已下的部分 staged() 里已经算了
        active[slot] = f"{len(batch)} 个 {n / 1e9:.1f} GB（{batch[0]['name']} …）"
        state["dl_batch"] = "；".join(active.values())
        log(f"开始下载一批（通道 {slot}）：{active[slot]}")
        th = threading.Thread(target=run_batch, args=(batch, n, n - got, slot, todo, tries), daemon=True)
        th.start(); threads.append(th)
        time.sleep(2)
    state["dl_batch"] = "全部下载完"


def run_batch(batch, n, resv, slot, todo, tries):
    global inflight
    try:
        t = time.time()
        aria2(batch, time.strftime("%H%M%S") + f"_{slot}", slot)
        ok = 0
        for it in batch:
            p = f"{DL}/{it['cat']}/{it['name']}"
            good = os.path.exists(p) and not os.path.exists(p + ".aria2") and (not it["size"] or os.path.getsize(p) == it["size"]
                    # OsmAnd 地图会更新，大小和清单对不上；zip 能打开就算下好（之后解压 / xz 还会校验）
                    or (it["post"] != "none" and zipfile.is_zipfile(p)))
            if good:
                ok += 1; pend(1); proc_q.put((it, p)); state["downloaded"] += 1
            else:
                tries[it["url"]] = tries.get(it["url"], 0) + 1
                if tries[it["url"]] < 4:
                    todo.append(it)
                else:
                    log("放弃（下载 4 次都没成功）", it["url"]); state["failed"].append(it["name"])
        log(f"本批完成（通道 {slot}）{ok}/{len(batch)}，{n / 1e6 / max(1, time.time() - t):.1f} MB/s")
    finally:
        with lock:
            inflight -= resv
        active.pop(slot, None)
        state["dl_batch"] = "；".join(active.values())


def pending_bytes():
    return inflight


def write_status():
    """status.json 给托盘窗口读（进度按整个清单算，中断重跑也接着算）；status.txt 给人直接看。"""
    el = time.time() - state["t0"]
    st = dict(phase=state["phase"], updated=time.strftime("%F %T"), hours=el / 3600,
              n_done=state["n0"] + state["moved"], n_total=state["n_total"],
              b_done=state["b0"] + state["src_done"], b_total=state["b_total"],
              rate=state["src_done"] / max(1, el), written=state["moved_bytes"],
              dl_batch=state["dl_batch"], moving=state["moving"], pending=pending_proc, queued=move_q.qsize(),
              staged=staged(), free_f=free("F:/"), free_g=free("G:/"), failed=state["failed"])
    json.dump(st, open(f"{STAGE}/status.json.tmp", "w", encoding="utf-8"), ensure_ascii=False)
    os.replace(f"{STAGE}/status.json.tmp", f"{STAGE}/status.json")
    s = (f"更新 {st['updated']}  {st['phase']}，本次已运行 {el / 3600:.1f} 小时\n"
         f"下载中：{st['dl_batch']}\n总进度 {st['n_done']} / {st['n_total']} 个，{st['b_done'] / 1e9:.1f} / {st['b_total'] / 1e9:.1f} GB\n"
         f"正在写：{st['moving'] or '无'}；待处理 {st['pending']}；待写入 {st['queued']}\n"
         f"暂存区 {st['staged'] / 1e9:.1f} GB；F 盘剩 {st['free_f'] / 1e9:.0f} GB；G 盘剩 {st['free_g'] / 1e9:.0f} GB\n"
         f"失败 {len(st['failed'])} 个：{', '.join(st['failed'][-5:])}\n")
    open(f"{STAGE}/status.txt", "w", encoding="utf-8").write(s)


def reporter():
    while True:
        try:
            write_status()
        except Exception as e:
            log("写进度失败", e)
        time.sleep(15)


def cleanup(items):
    """删掉 G 盘上原来直接往机械盘下载留下的半截文件。"""
    n = 0
    for it in items:
        for p in (gpath(it), f"{G}/{it['cat']}/{it['name']}"):
            if os.path.exists(p + ".aria2"):
                for q in (p, p + ".aria2"):
                    if os.path.exists(q): os.remove(q); n += 1
    for p in (f"{G}/03_Encyclopedia/wikipedia_en_all_maxi_2026-08.zim",):
        if os.path.exists(p) and os.path.getsize(p) == 0: os.remove(p); n += 1
    if os.path.isdir(f"{G}/_staging"):
        shutil.rmtree(f"{G}/_staging"); n += 1
    log(f"清理 G 盘半截文件 {n} 个")


def main():
    items = json.load(open(f"{STAGE}/inventory.json", encoding="utf-8"))
    cleanup(items)
    for it in items:  # 重跑时按 G 盘现状重新判断
        p = gpath(it)
        if os.path.exists(p) and not os.path.exists(p + ".part") and os.path.getsize(p) > 0 and \
                (it["post"] != "none" or not it["size"] or os.path.getsize(p) == it["size"]):
            it["status"] = "done"
    todo = [it for it in items if it["status"] == "todo"]
    local = [it for it in items if it["status"] == "local"]
    done = [it for it in items if it["status"] == "done"]
    state.update(n0=len(done), b0=sum(it["size"] for it in done), n_total=len(items), b_total=sum(it["size"] for it in items),
                 phase="下载中")
    log(f"开始：共 {len(items)} 个，G 盘已有 {sum(it['status'] == 'done' for it in items)}，F 盘直接拷 {len(local)}，"
        f"要下载 {len(todo)} 个 {sum(i['size'] for i in todo) / 1e9:.1f} GB")
    os.makedirs(f"{G}/_lists", exist_ok=True)
    # 上次中断时留在暂存区的：已处理好的直接搬，已下完的重新处理（aria2 会自己续传没下完的）
    for it in todo[:]:
        r = f"{READY}/{it['cat']}/{it['final']}"
        if os.path.exists(r):
            move_q.put((it, r, False)); todo.remove(it)
    for it in local:
        move_q.put((it, it["local"], True))
    pool = cf.ProcessPoolExecutor(XZ)
    threading.Thread(target=mover, daemon=True).start()
    threading.Thread(target=processor, args=(pool,), daemon=True).start()
    threading.Thread(target=reporter, daemon=True).start()
    downloader(todo)
    state["phase"] = "下载完了，正在处理和写入 G 盘"
    while pending_proc > 0 or move_q.unfinished_tasks:
        time.sleep(10)
    pool.shutdown()
    state["phase"] = "全部完成" if not state["failed"] else f"结束，{len(state['failed'])} 个失败"
    write_status()
    log(f"全部结束：写入 {state['moved']} 个；失败 {len(state['failed'])} 个 {state['failed']}")


if __name__ == "__main__":
    main()
