# -*- coding: utf-8 -*-
"""用本机 llama.cpp 跑题库。

用法：python run_eval.py <模型名=gguf路径> [...]
每个模型启动一次 llama-server（GPU），按题库逐题提问（贪心解码、关闭思考），
结果写到 results/<模型名>.jsonl，已有的题会跳过，可断点续跑。
"""
import json, os, subprocess, sys, time, urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
SERVER = "E:/AI/llama.cpp/llama-server.exe"
PORT = 8091
BANK = [json.loads(l) for l in open(os.path.join(HERE, "bank.jsonl"), encoding="utf-8")]

SYS_CLOSED = ("你是一个离线求生助手，用户可能处在没有网络、没有医生的环境。请用简体中文回答："
              "先给结论，再给关键数字和步骤，尽量简短。只说你确定的事实；不知道或不确定就直接说不知道，"
              "绝对不要编造药名、剂量、数字或方法。")
SYS_RAG = ("你是一个离线求生助手。下面给出从离线资料库检索到的资料。请根据资料，用简体中文简洁回答，并在用到资料的句子后标注出处编号，如 [1]。资料往往不会逐字回答问题：如果资料讲了相关的原理、做法、公式或数字，就把它们整理成能照着做的步骤；需要计算时，用资料里的公式和问题里的数字算出结果，并写出算式。只有资料里确实没有相关内容时，才回答“资料中没有提到”。不要用资料以外的知识补充数字、剂量或药名。")
SYS_KW = "把用户的中文问题改写成用于搜索英文百科和急救手册的英文关键词。只输出 3 到 6 个英文关键词，用空格分隔，不要输出任何其他内容。"
SYS_KW2 = ("把用户的中文问题改写成英文检索词，用来在英文的求生手册、急救手册和医学百科里查资料。"
           "写资料原文里会出现的具体词：动物、植物、工具、材料、动作、症状、做法，比如拔鸟毛写 plucking feathers，"
           "做担架写 improvised litter stretcher。不要写 safety、emergency、tips、guide、methods、survival 这类泛泛的词。"
           "只输出 3 到 6 个英文词，用空格分隔，不要输出其他内容。")


def messages(item):
    if item["cat"] == "kw":
        return [{"role": "system", "content": SYS_KW}, {"role": "user", "content": item["q"]}]
    if item.get("ctx"):
        return [{"role": "system", "content": SYS_RAG},
                {"role": "user", "content": f"资料：\n{item['ctx']}\n\n问题：{item['q']}"}]
    return [{"role": "system", "content": SYS_CLOSED}, {"role": "user", "content": item["q"]}]


def post(path, body, timeout=600):
    req = urllib.request.Request(f"http://127.0.0.1:{PORT}{path}", data=json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json"})
    return json.load(urllib.request.urlopen(req, timeout=timeout))


def wait_ready(proc, timeout=300):
    t = time.time()
    while time.time() - t < timeout:
        if proc.poll() is not None:
            raise RuntimeError("llama-server 退出了")
        try:
            if json.load(urllib.request.urlopen(f"http://127.0.0.1:{PORT}/health", timeout=3)).get("status") == "ok":
                return
        except Exception:
            pass
        time.sleep(2)
    raise RuntimeError("llama-server 启动超时")


def run_model(name, gguf):
    os.makedirs(os.path.join(HERE, "results"), exist_ok=True)
    out = os.path.join(HERE, "results", f"{name}.jsonl")
    done = set()
    if os.path.exists(out):
        done = {json.loads(l)["id"] for l in open(out, encoding="utf-8")}
    todo = [x for x in BANK if x["id"] not in done]
    if not todo:
        print(name, "已完成"); return
    log = open(os.path.join(HERE, "results", f"{name}.server.log"), "w", encoding="utf-8")
    proc = subprocess.Popen([SERVER, "-m", gguf, "--port", str(PORT), "-ngl", "99", "-c", "8192",
                             "--jinja", "-np", "1", "--no-webui"], stdout=log, stderr=subprocess.STDOUT)
    try:
        wait_ready(proc)
        with open(out, "a", encoding="utf-8", newline="\n") as f:
            for x in todo:
                t = time.time()
                body = {"messages": messages(x), "temperature": 0, "top_k": 1, "max_tokens": 900,
                        "chat_template_kwargs": {"enable_thinking": False}}
                try:
                    r = post("/v1/chat/completions", body)
                    ans = r["choices"][0]["message"].get("content") or ""
                    reasoning = r["choices"][0]["message"].get("reasoning_content") or ""
                    usage = r.get("usage", {})
                    rec = dict(id=x["id"], answer=ans, reasoning=reasoning,
                               completion_tokens=usage.get("completion_tokens"), prompt_tokens=usage.get("prompt_tokens"),
                               finish=r["choices"][0].get("finish_reason"), seconds=round(time.time() - t, 2))
                except Exception as e:
                    rec = dict(id=x["id"], answer="", error=str(e)[:300], seconds=round(time.time() - t, 2))
                f.write(json.dumps(rec, ensure_ascii=False) + "\n"); f.flush()
        print(name, "完成", len(todo), "题")
    finally:
        proc.terminate()
        try:
            proc.wait(20)
        except subprocess.TimeoutExpired:
            proc.kill()
        log.close()


if __name__ == "__main__":
    for arg in sys.argv[1:]:
        name, path = arg.split("=", 1)
        run_model(name, path)
