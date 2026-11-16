#!/usr/bin/env bash
# 核心资料包 v4：v3b 的切法（ARK_SEC_MIN=120）+ 恢复原先剔除的 10 本 + 无线电 / 机械 / 电力 / 医学 / 野外 / 澳洲新资料。
# 来源顺序就是去重优先级：先出现的保留（高质量的放前面）。ARK_QMIN=0.8 丢掉 OCR 乱码段。
# 之后算向量：ark-embed pack F:/ARK/models/embed/embeddinggemma-2-text-270m.litertlm packs/v4/ark-core.arkpack 768
set -e
cd "$(dirname "$0")"
C=chunks
ARK_QMIN=0.8 python build_pack.py ark-core "方舟核心资料（急救·医学·饮水·食物·求生·无线电·维修·能源）" en \
 "WTND / Hesperian 书籍：仅限非营利免费分发；美军、美国海军、FEMA、和平队手册及古登堡书籍：公有领域；MDWiki、维基百科、Stack Exchange、WikiCiv、energypedia、Restarters、Scoutwiki：CC BY-SA；zimgit 合集内各书版权归原作者" \
 packs/v4 \
 $C/mdwiki-core.jsonl $C/Where_There_Is_No_Doctor_2025.jsonl "$C/Where Women Have No Doctor (Hesperian).jsonl" \
 "$C/A Community Guide to Environmental Health (Hesperian).jsonl" $C/zimgit-medicine_en_2024-08.jsonl \
 "$C/ST 31-91B Special Forces Medical Handbook (US Army).jsonl" $C/zimgit-water_en_2024-08.jsonl \
 $C/zimgit-food-preparation_en_2025-04.jsonl $C/zimgit-post-disaster_en_2024-05.jsonl $C/zimgit-knots_en_2024-08.jsonl \
 "$C/Are You Ready - An In-depth Guide to Citizen Preparedness (FEMA).jsonl" \
 "$C/FM 3-25.26 Map Reading and Land Navigation (US Army).jsonl" "$C/Deadfalls and Snares (A. R. Harding, 1907).jsonl" \
 "$C/Steel Traps (A. R. Harding, 1907).jsonl" "$C/Camp Life in the Woods and the Tricks of Trapping (W. H. Gibson, 1881).jsonl" \
 "$C/Woodcraft and Camping (Nessmuk, 1920).jsonl" "$C/Shelters, Shacks and Shanties (D. C. Beard, 1914).jsonl" \
 "$C/Freshwater Fish Pond Culture and Management (Peace Corps).jsonl" \
 "$C/NEETS Module 1 - Matter, Energy, and Direct Current (US Navy).jsonl" \
 "$C/NEETS Module 2 - Alternating Current and Transformers (US Navy).jsonl" \
 "$C/NEETS Module 5 - Generators and Motors (US Navy).jsonl" \
 "$C/NEETS Module 10 - Wave Propagation, Transmission Lines, and Antennas (US Navy).jsonl" \
 "$C/NEETS Module 17 - Radio-Frequency Communications Principles (US Navy).jsonl" \
 "$C/FM 24-18 Tactical Single-Channel Radio Communications Techniques (US Army).jsonl" \
 "$C/TM 9-8000 Principles of Automotive Vehicles (US Army).jsonl" "$C/Construction Mechanic 3 & 2 (US Navy).jsonl" \
 $C/Wikipedia.jsonl $C/wikiciv_en_all_maxi_2025-11.jsonl $C/restarters_en_all_maxi_2026-10.jsonl \
 $C/energypedia_en_all_nopic_2026-06.jsonl $C/scoutwiki.org_en_all_maxi_2026-10.jsonl \
 $C/ham.stackexchange.com_en_all_2026-10.jsonl $C/outdoors.stackexchange.com_en_all_2026-10.jsonl \
 $C/mechanics.stackexchange.com_en_all_2026-10.jsonl
