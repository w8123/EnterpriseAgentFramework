# 睿池 AI 迎面跑来视频关键帧

## 成片规格

- 时长：6 秒
- 比例：9:16 竖屏
- 建议输出：1080 × 1920、24 或 30 fps、H.264 MP4
- 网站使用：静音自动播放、循环播放；三支视频横向并排时保持相同卡片尺寸
- 正式关键帧目录：`final-1080x1920/`

## 时间轴

| 时间 | 文件 | 动作 |
| --- | --- | --- |
| 0.0 秒 | `frame-01-0000ms-run-far.png` | 角色从较远处迎面跑来，右脚向前 |
| 1.4 秒 | `frame-02-1400ms-run-near.png` | 角色明显靠近，交换步伐继续跑动 |
| 2.8 秒 | `frame-03-2800ms-stop-blink.png` | 前景轻轻刹停，双眼闭合完成一次眨眼 |
| 4.1 秒 | `frame-04-4100ms-open-wave.png` | 睁眼、轻歪头，抬起左侧手掌挥手 |
| 5.6 秒 | `frame-05-5600ms-wave-ah.png` | 维持挥手，张开小嘴发出轻快的“啊” |
| 6.0 秒 | 使用第 5 帧延长 | 保持最终姿势约 0.4 秒，便于网页观看和循环衔接 |

## 视频生成提示词

```text
Create a polished 6-second vertical 9:16 mascot animation from the supplied ReachAI keyframes. Preserve the exact childlike mascot identity, cream rabbit cap, ReachAI-violet inner ears, charcoal goggles, cyan-blue-violet lens nodes, eye highlights, exactly three cheek dots, outfit, chest badge, lighting, and fixed pastel background.

Motion sequence: the mascot runs directly toward the camera with two readable alternating steps; it arrives in the foreground and makes a soft playful braking stop; both eyes close and reopen for one complete blink; the mascot tilts its head slightly and performs one gentle open-palm wave; finally it leans forward a little and opens a small cheerful round mouth as if softly saying hello or “ah”, then holds the final pose.

Keep the camera locked and the background completely stable. Use smooth natural ease-in and ease-out, subtle vertical body bounce, believable arm and leg arcs, soft ear follow-through, stable hands and facial features, and a clean contact shadow. Preserve the broad luminous orbital curve and oval floor glow without flicker. The animation must remain legible at small size when three vertical videos are displayed side by side on a website.

No text, no speech bubble, no added icons, no new props, no camera shake, no background motion, no background flicker, no color drift, no face redesign, no green ear interiors, no missing cheek dots, no extra fingers, no warped limbs, no motion smearing, no grain, no grid, no fabric microtexture, no moire.
```

## 使用建议

- 支持多关键帧的视频模型：按上表导入全部 5 帧。
- 只支持首尾帧的模型：先生成 `第 1 帧 → 第 3 帧` 的 2.8 秒片段，再生成 `第 3 帧 → 第 5 帧` 的 3.2 秒片段，最后无缝拼接。
- 三栏并排时不要给每个视频加入不同的复杂背景；统一使用本组象牙白、清新绿、蓝紫柔光背景，整体会更像一个连续模块。
- 视频容器建议保留 `aspect-ratio: 9 / 16`，并使用 `object-fit: cover`；如果站点裁切较激进，优先使用 `object-fit: contain`。
