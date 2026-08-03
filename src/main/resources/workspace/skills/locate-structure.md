---
id: locate-structure
name: 原版结构定位
description: 用原版 locate structure 查找最近生成结构的坐标
triggers:
  - 最近的末地城/要塞/村庄/海底神殿等结构在哪
  - 用 locate 查结构位置
  - 定位生成结构、找最近的 xx 建筑
---

# 原版结构定位

本 Skill 只用于查找原版生成结构。命名传送点、玩家传送等其他能力需要各自的任务 Skill；当前 Workspace 未提供时，不要猜测命令。

执行身份：`locate structure` **必须**用对话玩家 `player`（依赖当前位置与维度）。<br>
调用任何 `run_command` 前，先按 `AGENTS.md` 核对身份、白名单与结果语义。<br>
`command` 用全称（如 `locate structure <id>`，不要写缺 `structure` 的简写）。

## 命令

```
locate structure <structure_id>
```

- 只做 **structure**。不要用 `locate biome` / `locate poi`，除非玩家明确要求且当前白名单允许（默认未放行）。
- `structure_id` 用小写 id；可带 `minecraft:` 前缀（规范化后会小写）。
- **禁止**控制台代跑；**禁止**编造坐标。玩家分发不回传游戏内反馈：`run_command` 返回 `dispatched` 时只说明命令已提交，坐标和距离以玩家屏幕上的原版反馈为准。

## 口语 → id 对照（Paper / Folia 26.2）

| 玩家说法 | 使用 id | 备注 |
| --- | --- | --- |
| 末地城 | `end_city` | 须在末地维度执行才有意义 |
| 要塞 / 传送门要塞 | `stronghold` | 主世界 |
| 下界要塞 | `fortress` | 下界 |
| 堡垒遗迹 | `bastion_remnant` | 下界 |
| 村庄（任意） | `#minecraft:village` | tag；若服务端拒收 tag 再改具体 id |
| 平原/沙漠/雪原/热带草原/针叶林村庄 | `village_plains` / `village_desert` / `village_snowy` / `village_savanna` / `village_taiga` | 玩家指定类型时用 |
| 海底神殿 | `monument` | |
| 林地府邸 | `mansion` | |
| 海底废墟 | `ocean_ruin_cold` / `ocean_ruin_warm` 或 tag `#minecraft:ocean_ruin` | |
| 掠夺者前哨 | `pillager_outpost` | |
| 远古城市 | `ancient_city` | 主世界深层 |
| 试炼密室 | `trial_chambers` | |
| 废弃传送门 | tag `#minecraft:ruined_portal` 或具体 `ruined_portal_*` | |
| 埋藏的宝藏 | `buried_treasure` | |
| 沼泽小屋 | `swamp_hut` | |
| 沙漠神殿 / 丛林神庙 / 雪屋 | `desert_pyramid` / `jungle_pyramid` / `igloo` | |

歧义时先问清维度或结构类型再执行。维度不对时如实说明可能找不到，不要编位置。

## 执行示例

- 「最近的末地城在哪」→ `command: "locate structure end_city"`，对话玩家 `player`，`intent: "定位最近的末地城"`
- 「最近的村庄」→ `command: "locate structure #minecraft:village"`；若失败再改具体 `village_plains` 等并说明

## 边界

- `end_city` 等是结构 id，不是命名传送点；不要把结构 id 交给其他命令。
- 命名传送点、玩家之间的传送或其他服务器插件能力不属于本 Skill；没有对应 Skill 时，说明当前 Workspace 未提供该能力。
