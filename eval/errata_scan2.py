# -*- coding: utf-8 -*-
"""更精确的过时急救做法检索：按主题正则命中英文原文，输出候选段落全文供人工判读。
用法: python eval/errata_scan2.py [包路径] [输出json]
"""
import sqlite3, zlib, re, sys, json, collections

PACK = sys.argv[1] if len(sys.argv) > 1 else 'data/packs/zh/ark-core.arkpack'
OUT = sys.argv[2] if len(sys.argv) > 2 else 'eval/errata_cand.json'

SNAKE = r'(snake|viper|rattler|rattlesnake|cobra|mamba|krait|adder|copperhead|cottonmouth|moccasin|envenom|venom|fang)'
P = {
 # 毒蛇咬伤
 'snake_cut': SNAKE + r'.{0,400}\b(incision|incise|cut (across|through|into|over|an? ?x|the (bite|fang|skin|wound))|make (a|an|small) (cut|incision|slit)|slash|lance)|'
              r'\b(incision|incise|make (a|an|small) (cut|incision))s?.{0,200}' + SNAKE,
 'snake_suck': SNAKE + r'.{0,400}\b(suck|suction|extractor|sawyer|aspirat|draw out the (venom|poison)|squeeze out the (venom|poison))|'
               r'\b(suck|suction|extractor)\w*.{0,200}' + SNAKE,
 'snake_ice': SNAKE + r'.{0,300}\b(ice|cold pack|cold compress|cryotherapy|cool the)\b|\b(ice|cold pack)\b.{0,150}' + SNAKE,
 'snake_band': SNAKE + r'.{0,400}\b(tourniquet|constricting band|constriction band|ligature|tie (a|the) (band|cord|string|cloth)|tight band)|'
               r'\b(tourniquet|constricting band|constriction band|ligature)s?\b.{0,300}' + SNAKE,
 'snake_misc': SNAKE + r'.{0,300}\b(gunpowder|kerosene|potassium permanganate|electric shock|stun gun|alcohol|whisk(e)?y|black stone|snake ?stone|cauteri[sz])',
 # 止血带
 'tq_loosen': r'\b(loosen|release|relax|slacken)\w*\b.{0,80}tourniquet|tourniquet.{0,200}\b(loosen|release|relax|slacken)\w*\b.{0,80}(minutes|min\b|every)',
 'tq_lastresort': r'tourniquet.{0,150}(last resort|only as a last|only when all other|never use a tourniquet|should not be used)',
 # 烧伤
 'burn_grease': r'burn.{0,250}\b(butter|grease|lard|oil|toothpaste|egg white|flour|honey|soy sauce|mustard|vaseline|petroleum jelly|ointment|tannic|tea)\b|'
                r'\b(butter|grease|lard|toothpaste|tannic acid)\b.{0,150}burn',
 'burn_ice': r'burn.{0,150}\b(ice|ice water|iced)\b|\bice\b.{0,80}burn',
 # 流鼻血
 'nose_back': r'(nose ?bleed|epistaxis|bleeding (from the|from) nose|nosebleed).{0,300}(head back|tilt\w* (the )?head|lie (down|flat)|lying down|back of the neck|cold (key|compress) (on|to) the (neck|back))|'
              r'(head back|tilt\w* (the )?head back).{0,200}(nose|epistaxis)',
 # 癫痫
 'seizure_mouth': r'(seizure|convuls|fit\b|fits\b|epilep).{0,400}(between (the|his|her) teeth|in(to)? (the|his|her) mouth|bite (his|her|the) tongue|swallow(ing)? (his|her|the) tongue|tongue depressor|padded stick|mouth gag|bite block|wedge)|'
                  r'(between (the|his|her) teeth|tongue blade|padded stick).{0,300}(seizure|convuls|epilep)',
 'seizure_restrain': r'(seizure|convuls|epilep).{0,200}\b(restrain|hold (him|her|them) down|hold down)',
 # 低体温/酒精
 'alcohol_cold': r'(alcohol|whisk(e)?y|brandy|rum|liquor|spirits).{0,200}(warm|hypotherm|cold|chill|frost)|(hypotherm|frostbit|cold weather|chilled).{0,250}(alcohol|whisk(e)?y|brandy|liquor)',
 'hypo_rewarm': r'hypotherm.{0,400}(rub|massage|hot bath|hot water|rapid(ly)? rewarm|exercise|walk around|warm (the )?(arms|legs|extremities))',
 # 冻伤
 'frost_rub': r'(frost ?bit|frozen (part|feet|hands|toes|fingers|ear|nose|skin|flesh)|frost ?nip|chilblain).{0,400}\b(rub|rubbing|massag|snow|fire|stove|heater|hot water|exercise|walk)\w*|'
              r'\b(rub|rubbing)\w*\b.{0,100}(snow|frost)',
 # 水母
 'jelly': r'(jellyfish|jelly-fish|man-of-war|man o. war|portuguese man|box jelly|sea wasp|nematocyst|coelenterate|cnidaria|stinging cell|sea anemone|fire coral).{0,500}(urin|pee|fresh ?water|rub|alcohol|ammonia|meat tenderi[sz]er|vinegar|sand|baking soda|hot water|shav)',
 # 催吐
 'emesis': r'ipecac|induce (vomiting|emesis)|make (him|her|them|the (person|patient|victim|child)) (vomit|throw up)|cause (him|her|them|the (person|patient|victim)) to vomit|finger down (the|his|her) throat|tickle (the|his|her) throat|salt water.{0,40}vomit|mustard.{0,40}vomit',
 # 过氧化氢/酒精冲伤口
 'peroxide': r'(hydrogen peroxide|peroxide|iodine|alcohol|mercurochrome|merthiolate|gentian violet|lysol|carbolic|bleach).{0,150}\b(wound|cut|laceration|sore|abrasion|scrape|injur)|\b(wound|cut|laceration|abrasion)s?\b.{0,150}(hydrogen peroxide|peroxide|mercurochrome|merthiolate)',
 # 心肺复苏
 'cpr_old': r'(15|fifteen|5|five|30)\s*(chest )?compressions?.{0,60}(2|two|1|one)\s*(rescue )?(breath|ventilation|inflation)s?|'
            r'(80|60|100|eighty|sixty|one hundred) (compressions|times|per minute|a minute).{0,100}(compress|chest|cpr|heart)|(compress|chest|cpr|heart).{0,100}(80|60|eighty|sixty)\s*(-|to)?\s*(100)?\s*(compressions|times|per minute|a minute)|'
            r'(1\.?5|1 1/2|one and a half|1½|one-and-one-half|1 1/2 to 2|1\.5 to 2)\s*(-|to)?\s*(2)?\s*inch|'
            r'(4|3) to 5 (cm|centimet)|(sternum|breastbone|chest).{0,80}(depress|compress)\w*.{0,40}(1|one|1\.5|1 1/2) ?(to|-) ?(1 1/2|2|1\.5) inch|'
            r'(check|feel) (for )?(the )?(carotid )?pulse.{0,80}(before|then) (start|begin)\w* (cpr|compress)|precordial thump|blind finger sweep|finger sweep|sweep (the|his|her) mouth|'
            r'cpr.{0,200}(look,? listen,? and feel)|(look,? listen,? and feel).{0,200}(cpr|breath)',
 # 气道/窒息
 'choking_old': r'(chok|airway obstruct|foreign body).{0,300}(slap (him|her|them|on) the back|blind(ly)? (finger|sweep)|hold (the child|the baby|him|her) upside down|turn (the child|the baby|him|her) upside down|abdominal thrust.{0,40}(only|instead of back)|never (give|use) back (blows|slaps))',
 # 溺水
 'drown_drain': r'(drown|near-drown|submersion|rescued from the water|water in the lungs).{0,400}(drain|empty|get the water out|expel (the )?water|press(ing)? (on )?(the )?(stomach|belly|abdomen)|upside down|over (your|his|a) (knee|shoulder|barrel)|heimlich|abdominal thrust|lay .{0,30} face down|head lower than|hold .{0,20} by the (feet|legs))',
 # 中暑/热射病
 'heat_old': r'(heat ?stroke|sunstroke|heat exhaustion|heat cramp|hyperthermi).{0,400}(salt tablet|salt pill|alcohol (rub|sponge|bath)|rubbing alcohol|sponge.{0,30}alcohol|wrap .{0,30}(wet )?(sheet|blanket)|aspirin|acetaminophen|paracetamol|antipyretic|do not cool too|shiver|cold water immersion.{0,40}(dangerous|not)|ice water.{0,40}(dangerous|never|not))|'
             r'salt tablets?',
 # 蜘蛛/蝎子
 'spider_scorp': r'(spider|black widow|brown recluse|redback|funnel-?web|scorpion|tarantula).{0,400}(tourniquet|constricting band|incision|cut .{0,20}(bite|sting)|suck|suction|extractor|ice|cold pack|cauteri|excis|antivenin|calcium gluconate|dapsone|steroid|electric)',
 # 骨折/脱位复位
 'fracture_reduce': r'(fracture|broken bone|dislocat).{0,400}(reduce|set the (bone|fracture|break)|straighten|traction|pull .{0,40}(back )?into (place|position|line)|realign|put .{0,30} back in(to)? place)|'
                    r'(set|setting) (a|the) (broken|fractured) (bone|limb|arm|leg)',
 # 其他：脊柱固定/休克体位/扭伤/蜂蜇/蜱虫/眼睛/牙齿/化学烧伤/咬伤/头部损伤
 'tick_old': r'tick.{0,300}(match|cigarette|burn(ing)? (it|the tick)|hot (needle|nail|match)|petroleum jelly|vaseline|nail polish|kerosene|gasoline|alcohol|oil|grease|twist)',
 'sting_scrape': r'(bee|wasp|stinger|sting).{0,300}(scrape|do not (squeeze|pinch|use tweezers)|don.t (squeeze|pinch|use tweezers)|never (squeeze|pinch))',
 'shock_trend': r'(shock).{0,300}(trendelenburg|raise (the )?(feet|legs)|elevate (the )?(feet|legs|foot of))|(raise|elevate) (the )?(feet|legs).{0,200}shock',
 'shock_water': r'shock.{0,300}(give .{0,40}(water|fluids|drink|tea|coffee|alcohol|whisk)|sips? of water|warm drink)',
 'sprain_heat': r'(sprain|strain|bruise|swell).{0,300}(hot (pack|water|compress)|heat|massage|rub)',
 'head_sleep': r'(head injury|concussion|head wound|knocked out|unconscious).{0,300}(keep .{0,20} awake|do not (let|allow) .{0,20} sleep|don.t let .{0,20} sleep|wake .{0,30} every)',
 'chem_neutral': r'(chemical burn|acid burn|alkali burn|lye|caustic|acid (in|on|splash)).{0,300}(neutrali[sz]|vinegar|baking soda|bicarbonate|lime juice)',
 'eye_old': r'(eye).{0,200}(both eyes|cover both|patch both|eye ?patch|rub the eye|cow.s milk|breast milk|urine)',
 'spine_old': r'(spinal|spine|neck injur|cervical).{0,300}(do not move|never move|log ?roll|backboard|long board|cervical collar|c-collar)',
 'bite_cauter': r'(bite|wound).{0,200}(cauteri[sz]e|hot iron|red-hot|burn the wound|gunpowder|carbolic|nitric acid)',
 'mouth_to_mouth': r'(silvester|holger.nielsen|schafer|sch.fer|back.pressure arm.lift|chest.pressure arm.lift|prone pressure)',
 'cold_water_hypo': r'(do not|don.t|never) (put|place|warm|immerse) .{0,60}(hot|warm) (water|bath)',
 'charcoal': r'(universal antidote|burnt toast|activated charcoal.{0,80}(all|every|any) (poison|case))',
}

def main():
    db = sqlite3.connect(PACK)
    titles = {r[0]: r[1] for r in db.execute('select id, title_en from docs')}
    cnt = collections.Counter(); out = collections.defaultdict(list)
    comp = {k: re.compile(v, re.I | re.S) for k, v in P.items()}
    for cid, did, t in db.execute('select id, doc_id, text_en from chunks where text_en is not null'):
        e = zlib.decompress(t).decode('utf-8', 'ignore')
        for k, rx in comp.items():
            m = rx.search(e)
            if m:
                cnt[k] += 1
                out[k].append({'id': cid, 'doc': titles.get(did), 'hit': e[max(0, m.start()-80):m.end()+80].replace('\n', ' ')})
    for k in P:
        print(k, cnt[k])
    json.dump(out, open(OUT, 'w', encoding='utf-8'), ensure_ascii=False, indent=1)

if __name__ == '__main__':
    main()
