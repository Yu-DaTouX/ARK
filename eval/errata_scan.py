import sqlite3,zlib,re,collections
db=sqlite3.connect('data/packs/zh/ark-core.arkpack')
P={
 'snake_suction':r'(suction|extractor|suck (out )?the venom|cut .{0,30}(fang|bite)|incision.{0,40}(bite|fang))',
 'snake_ice':r'(ice|cold pack).{0,40}(snake|bite)|(snake|bite).{0,60}\bice\b',
 'snake_tourniquet':r'(snake|venom).{0,120}(tourniquet|constricting band)|(tourniquet|constricting band).{0,120}(snake|venom)',
 'ipecac':r'ipecac|induce vomiting',
 'burn_butter':r'butter.{0,40}burn|burn.{0,60}(butter|grease|oil)\b',
 'nosebleed_back':r'(tilt|lean).{0,20}head back',
 'seizure_mouth':r'(something|object|stick|spoon).{0,40}(in|between).{0,20}(mouth|teeth).{0,60}(seizure|convuls|fit)|(seizure|convuls).{0,120}(tongue|between the teeth)',
 'alcohol_cold':r'(alcohol|whiskey|brandy).{0,60}(warm|hypotherm|cold)',
 'frostbite_rub':r'rub.{0,40}(frostbit|snow)|(frostbit).{0,60}rub',
 'jelly_urine':r'urinat.{0,40}(sting|jellyfish)|(jellyfish).{0,80}urin',
 'peroxide':r'hydrogen peroxide.{0,60}(wound|cut)',
 'tourniquet_last':r'tourniquet.{0,80}(last resort|only as a last)',
 'tourniquet_loosen':r'(loosen|release).{0,40}tourniquet.{0,60}(every|minutes)',
 'cpr_ratio':r'15 compressions|5 compressions.{0,20}1 breath|(rate of )?(60|80) compressions',
 'heimlich_back':r'(back blows?).{0,80}(choking)',
}
cnt=collections.Counter(); ex={}
for cid,t in db.execute('select id,text_en from chunks where text_en is not null'):
    e=zlib.decompress(t).decode('utf-8','ignore')
    for k,p in P.items():
        m=re.search(p,e,re.I|re.S)
        if m:
            cnt[k]+=1; ex.setdefault(k,[]).append((cid,e[max(0,m.start()-120):m.end()+120].replace('\n',' ')))
for k,v in cnt.most_common(): print(k,v)
import json; json.dump(ex,open(r'C:/Users/YuDaTou/AppData/Local/Temp/scan.json','w',encoding='utf-8'),ensure_ascii=False,indent=1)
