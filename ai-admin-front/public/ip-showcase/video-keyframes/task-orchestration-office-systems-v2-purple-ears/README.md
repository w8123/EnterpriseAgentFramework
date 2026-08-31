# 睿池 AI「跨系统任务编排」办公装版关键帧

## 成片规格

- 时长：6 秒
- 比例：9:16 竖屏
- 建议输出：1080 × 1920、24 或 30 fps、H.264 MP4
- 正式关键帧：`final-1080x1920/`
- ImageGen 原始输出：`source-generated/`
- 展示方式：与其他竖屏视频横向三栏并排，静音自动播放、循环播放

## 时间轴

| 时间 | 文件 | 动作 |
| --- | --- | --- |
| 0.0 秒 | `frame-01-0000ms-cards-scattered.png` | OA、eHR、ERP 三张系统卡无序漂浮，角色观察任务 |
| 1.3 秒 | `frame-02-1300ms-goggles-activate.png` | 角色轻触护目镜，镜片节点亮起并识别系统卡 |
| 2.7 秒 | `frame-03-2700ms-sweep-align.png` | 角色挥手，蓝紫光带把卡片收拢为有序队列 |
| 4.2 秒 | `frame-04-4200ms-flow-complete.png` | OA → eHR → ERP 形成流程，末端出现独立完成标记 |
| 5.5 秒 | `frame-05-5500ms-thumbs-up.png` | 角色面向镜头竖起大拇指 |
| 6.0 秒 | 延长第 5 帧 | 保持最终姿势约 0.5 秒 |

## 卡片语义

- `OA`：紫色卡片，审批文件与勾选图案。
- `eHR`：蓝色卡片，人员档案与日历图案。
- `ERP`：清新绿色卡片，财务表格与 `¥` 图案。
- 三张卡片之外，流程末端仅保留一个独立圆形完成标记。

## 角色固定规范

- 奶油色薄款兔子帽，一只耳朵直立、一只耳朵折下。
- 两只兔耳内侧均为睿池紫 `#795CFF`。
- 深灰护目镜、蓝青紫节点图案、深海军蓝胶囊眼睛。
- 画面左侧眼睛为青色菱形高光，右侧眼睛为紫色菱形高光。
- 画面右侧脸颊固定三个蓝青紫圆点。
- 米杏色短袖轻办公外套、白色圆领内搭、深灰短裤、米白运动鞋。
- 睿池 AI 胸章保持原始蓝紫色，不随服装或卡片配色变化。
- 表面光滑，仅保留大面积明暗；禁止浮雕、织物网格、微纹理和摩尔纹。

## 视频生成提示词

```text
Create a polished 6-second vertical 9:16 ReachAI mascot animation using the supplied five keyframes. Preserve the exact childlike mascot identity, body proportions, cream rabbit cap, exactly one upright ear and one folded ear, ReachAI-violet #795CFF inner ears, charcoal goggles, cyan-blue-violet lens-node motifs, deep navy capsule eyes with the cyan diamond highlight in the viewer-left eye and violet diamond highlight in the viewer-right eye, exactly three cyan/blue/violet dots on the viewer-right cheek, warm oatmeal short-sleeve office jacket, white crew-neck T-shirt, dark charcoal shorts, cream sneakers with restrained navy/violet accents, and the original blue-purple ReachAI chest badge.

Motion sequence: three large frosted system cards float gently in disorganized positions. The violet card must keep the exact label "OA" and its document-with-check icon. The cyan-blue card must keep the exact label "eHR" and its employee-profile plus calendar icon. The pale-lime card must keep the exact label "ERP" and its spreadsheet plus ¥ icon. The mascot studies the cards, raises one hand, and lightly taps the goggles; the connection nodes inside both lenses glow brighter. The mascot makes one broad smooth hand sweep. A thick cyan-to-violet luminous ribbon follows the hand and guides the three cards into a clean vertical workflow in the fixed order OA, eHR, ERP. A separate small circular completion badge with a green check appears at the endpoint below ERP without covering any card. The mascot turns toward the viewer, gives one clear thumbs-up, smiles, and holds the final pose.

Keep the camera completely locked. Keep the pale-violet left edge, warm ivory center, pale-cyan right edge, broad background orbital curve, and oval floor glow stable without flicker or movement. Preserve exactly three system cards throughout the animation. Preserve every label and icon exactly; do not morph, translate, duplicate, scramble, or replace any letter or symbol. Use smooth ease-in and ease-out, subtle anticipation, gentle card drift, a readable hand arc, soft ear follow-through, stable hands and face, and a clean contact shadow. Keep the result readable when three vertical videos are shown side by side at small size on a website.

No extra text, no fourth card, no new icons, no desk, no laptop, no camera movement, no background motion, no flicker, no color drift, no face redesign, no green clothing, no green ear interiors, no missing cheek dots, no extra fingers, no warped hands, no motion smearing, no fabric weave, no embossed texture, no grid, no microtexture, no halftone, no mosaic, no moire, no grain, no watermark.
```

## 本次关键帧图像生成提示词集合

共同提示：以旧版五帧分别作为动作与构图锚点，以办公桌参考图作为服装锚点；保持角色身份、背景、机位和光线，只把绿色运动上衣替换为米杏色短袖轻办公外套、白色内搭、深灰短裤和米白鞋，并保留原始蓝紫胸章。

- 第 1 帧：三张卡片保持散落位置，写入 `OA`、`eHR`、`ERP` 及对应的大图标。
- 第 2 帧：保持卡片和文字，角色触碰护目镜，镜片节点出现克制的蓝白激活光。
- 第 3 帧：卡片按照 OA → eHR → ERP 向左侧竖向收拢，手部扫出蓝紫光带。
- 第 4 帧：三张卡形成清晰竖向流程，ERP 下方增加独立圆形完成标记，不能用完成标记替换 ERP 卡。
- 第 5 帧：流程保持不变，角色面向镜头竖起大拇指并微笑。

统一限制：卡片标签必须逐字准确且各出现一次；不增加其他文字；无绿色服装；无浮雕、织物纹理、网格、马赛克、摩尔纹、颗粒和水印。

## 使用建议

- 支持多关键帧：按时间轴导入全部 5 帧，并开启首尾帧一致性或角色参考功能。
- 只支持首尾帧：先生成第 1 → 第 3 帧的 2.7 秒片段，再生成第 3 → 第 5 帧的 3.3 秒片段，最后拼接。
- 若视频模型容易改字，把文字一致性设为最高优先级；不要允许模型额外补充系统名称或真实 UI。
- 网站容器使用 `aspect-ratio: 9 / 16`；三栏并排时保持相同圆角、间距和裁切方式。
