# 睿池 AI「把混乱变清楚」视频关键帧

## 成片规格

- 时长：6 秒
- 比例：9:16 竖屏
- 建议输出：1080 × 1920、24 或 30 fps、H.264 MP4
- 正式关键帧目录：`final-1080x1920/`
- 网站使用：与第一支迎面跑来视频横向并排，静音自动播放、循环播放

## 时间轴

| 时间 | 文件 | 动作 |
| --- | --- | --- |
| 0.0 秒 | `frame-01-0000ms-cards-scattered.png` | 紫、蓝、绿三张任务卡片无序漂浮，角色观察思考 |
| 1.3 秒 | `frame-02-1300ms-goggles-activate.png` | 角色轻触护目镜，镜片节点亮起 |
| 2.7 秒 | `frame-03-2700ms-sweep-align.png` | 角色横向挥手，粗蓝紫光带带动卡片收拢 |
| 4.2 秒 | `frame-04-4200ms-flow-complete.png` | 三张卡片排成流程，最后一张出现完成标记 |
| 5.5 秒 | `frame-05-5500ms-thumbs-up.png` | 角色面向镜头竖起大拇指 |
| 6.0 秒 | 使用第 5 帧延长 | 保持最终姿势约 0.5 秒 |

## 视频生成提示词

```text
Create a polished 6-second vertical 9:16 ReachAI mascot animation using the supplied keyframes. Preserve the exact childlike mascot identity, body proportions, cream rabbit cap, ReachAI-violet inner ears, dark goggles, cyan-blue-violet lens nodes, navy eyes and their diamond highlights, exactly three cheek dots, outfit, original chest badge, lighting, and smooth 3D toy-render finish.

Motion sequence: three large frosted task tiles—violet, cyan, and pale lime—float gently in disorganized positions while the mascot studies them. The mascot raises one hand and lightly taps the edge of the goggles; the connection nodes inside both lenses glow brighter. The mascot then makes one broad, smooth horizontal hand sweep. A thick cyan-to-violet luminous ribbon follows the hand and guides the three tiles into a clean vertical workflow column. The tiles become upright in the order violet, cyan, pale lime. A single large green check mark appears on the final pale-lime tile. The mascot looks back toward the viewer, gives one clear thumbs-up, smiles, and holds the final pose.

Keep the camera completely locked. Keep the pale-violet left edge, warm ivory and light-blue center, pale-cyan right edge, broad background orbital curve, and oval floor glow stable without any flicker or movement. Preserve exactly three task tiles throughout the animation. Their colors, sizes, rounded glass materials, and order must remain consistent. Use smooth ease-in and ease-out, subtle character anticipation, gentle tile drift, a readable hand arc, soft ear follow-through, stable hands and face, and a clean contact shadow. The result must remain readable at small size when three vertical videos are displayed side by side on a website.

No text, no labels, no speech bubbles, no extra cards, no new icons, no desk, no laptop, no camera movement, no background motion, no flicker, no color drift, no face redesign, no green ear interiors, no missing cheek dots, no extra fingers, no warped hands, no thin tangled lines, no motion smearing, no grain, no grid, no microtexture, no moire.
```

## 使用建议

- 支持多关键帧：按上表导入全部 5 帧。
- 只支持首尾帧：先生成 `第 1 帧 → 第 3 帧` 的 2.7 秒片段，再生成 `第 3 帧 → 第 5 帧` 的 3.3 秒片段，最后拼接。
- 卡片移动保持缓慢、路径清晰，不要让视频模型额外生成文字或真实办公界面。
- 视频容器使用 `aspect-ratio: 9 / 16`；三栏并排时保持相同圆角、间距和裁切方式。
