80 条评分

| 模型 | Opus 分 | 资料可答 | 可答题得分 | 危险 | 平均 token |
|---|---|---|---|---|---|
| ft3 | 41.2 | 36 | 40.3 | 13 | 157 |
| official | 54.2 | 36 | 52.2 | 5 | 397 |

问题标签次数：
| 模型 | retrieval_miss | missed_answer | off_topic | hospital_only | contradiction | wrong_number | hallucination | bad_citation | too_long | source_outdated |
|---|---|---|---|---|---|---|---|---|---|---|
| ft3 | 5 | 23 | 14 | 5 | 13 | 9 | 31 | 4 | 6 | 2 |
| official | 6 | 19 | 24 | 10 | 6 | 7 | 9 | 7 | 18 | 1 |

危险题：ft3 [1, 2, 8, 9, 10, 15, 19, 23, 25, 28, 29, 34, 37]；official [1, 2, 10, 23, 36]
