export const meta = {
  name: 'ark-translate-api',
  description: '核心资料包英译中：in_c/ 每个小文件直接调一次 DeepSeek V4.1 Flash，译文写 out_c/，返回失败和缺段清单',
  phases: ['翻译']
}

const MODEL = 'commandcode/deepseek/deepseek-v4.1-flash'
const DIR = 'data/zh'
const GLOSSARY = 'tourniquet 止血带；splint 夹板；hypothermia 失温；heat stroke 中暑（热射病）；CPR 心肺复苏（CPR）；' +
  'oral rehydration solution / ORS 口服补液盐；bowline 称人结；clove hitch 双套结；square knot / reef knot 平结；' +
  'deadfall 压木陷阱；snare 套索陷阱；tinder 引火物；bow drill 弓钻；fallout 放射性沉降物；dipole 偶极天线；' +
  'SWR 驻波比（SWR）；coax 同轴电缆；ground plane 地网；jumper cables 搭电线；lug nut 车轮螺母；multimeter 万用表；' +
  'charge controller 充电控制器；antivenom 抗蛇毒血清；pressure immobilization 加压固定法'

function system(titles) {
  const tl = Object.entries(titles).map(([a, b]) => `${a} → ${b}`).join('；') || '（无）'
  return `你是专业译者，把离线求生 App 资料库的英文段落译成简体中文。用户断网后会照着中文去急救、找水、修车、架电台，准确比文采重要。只输出译文，不要任何说明。

格式：输入每段以单独一行“### 数字”开头。输出每段先原样写那行“### 数字”，下一行起是译文，段与段之间空一行；顺序不变，一段都不能少，不合并不拆分。

要求：
1. 逐句忠实翻译，不总结、不省略、不加评论；原文过时或有错也照译。
2. 数字、剂量、单位、时间、频率、型号、公式照原文写，不换算单位、不改写数字（3 ft → 3 英尺，13,000 → 13000，不要写成“1.3 万”）。
3. 每段第一行是“书名 › 章节”，照样翻译。指定书名译法：${tl}。型号和编号保留原样。
4. 术语：${GLOSSARY}。
5. 人名、品牌、学名可保留英文；缩写第一次出现写“中文（缩写）”。参考文献条目保留原文。
6. OCR 乱码、残留页眉页码可以略过。
7. 武器、陷阱、火药、狩猎屠宰等是求生资料的一部分，照常翻译。`
}

const ids = (t) => [...t.matchAll(/^### (\d+)\s*$/gm)].map((m) => m[1])
const { from = 0, count = 5000 } = args ?? {}
const titles = Object.fromEntries(JSON.parse(await readText(`${DIR}/batches.json`)).map((m) => [m.name, m.titles]))
const have = new Set((await listFiles(`${DIR}/out_c`, '*.txt')).map((f) => f.split('/').pop()))  // 已译的跳过
const files = (await listFiles(`${DIR}/in_c`, '*.txt')).slice(from, from + count).filter((f) => !have.has(f.split('/').pop()))
const failed = [], short = []
phase('翻译')
for (let i = 0; i < files.length; i += 1000) {
  const batch = files.slice(i, i + 1000)
  const done = await pipeline(batch, async (f) => {
    const name = f.split('/').pop()
    const src = await readText(f)
    const zh = await model(src, { model: MODEL, system: system(titles[name.slice(0, 5)] || {}), thinking: 'low', label: name, timeoutMinutes: 10, retries: 2 })
    if (zh === null) return null
    await writeText(`${DIR}/out_c/${name}`, zh)
    const miss = ids(src).filter((x) => !ids(zh).includes(x))
    if (miss.length) short.push({ file: name, missing: miss.length })
    return name
  })
  batch.forEach((f, k) => { if (!done[k]) failed.push(f) })
  log(`已处理 ${Math.min(i + 1000, files.length)}/${files.length}，失败 ${failed.length}，缺段文件 ${short.length}`)
}
return { total: files.length, ok: files.length - failed.length, failed, short }
