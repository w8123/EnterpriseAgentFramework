# Third-Party Notices — ai-admin-front

本文件记录管理端源码中复制或移植的第三方代码与随包分发的视觉素材。通过 npm 安装的依赖以各自包内的许可证为准，不在此重复。

## 主题背景（项目自有 SVG）

- 科技紫、云雾银、暖珊瑚使用项目内绘制的 SVG 曲面，分别提供浅色与深色版本，位于 `public/theme-wallpapers/{lavender,silver,coral}-{light,dark}.svg`。
- 这些背景不依赖外部图片服务；品牌键 `nebula-violet`、`coral-rose` 分别对应云雾银、暖珊瑚，保持已保存的主题选择和 SDK 预设兼容。

## 主题壁纸（Unsplash）

- 许可证：[Unsplash License](https://unsplash.com/license)，2026-10-20 核对。允许免费商业使用、修改与分发；不能原样转卖图片或汇编成竞争图片服务。
- 当前用途：极光青、翡翠绿、日冕金、深海蓝的管理端背景与页头。原紫色与玫紫素材保留在资源目录中。
- 处理：通过来源的图片服务导出最大宽度 2560px、质量 82 的 WebP，随应用本地打包，保留原图构图与色彩。阅读保护使用组件局部表面，不依赖运行时外链。
- 来源、下载参数、字节数、SHA-256 与品牌映射见 `public/theme-wallpapers/manifest.json`。

| 本地资源 | 作者 | 原图 |
| --- | --- | --- |
| `public/theme-wallpapers/violet-waves.webp` | Milad Fakurian | [紫色曲面](https://unsplash.com/photos/an-abstract-purple-background-with-wavy-lines-DX7pT_guAyE) |
| `public/theme-wallpapers/ice-blue.webp` | Rohit Choudhari | [冰蓝流线](https://unsplash.com/photos/abstract-blue-and-white-wavy-lines-background-_E6sXQHsgQc) |
| `public/theme-wallpapers/rose-glow.webp` | Milad Fakurian | [玫紫光影](https://unsplash.com/photos/abstract-purple-and-pink-gradient-waves-nY14Fs8pxT8) |
| `public/theme-wallpapers/sage-folds.webp` | Pawel Czerwinski | [灰绿叠层](https://unsplash.com/photos/layered-sage-green-paper-waves-4Ui_7KqNtFg) |
| `public/theme-wallpapers/gold-curves.webp` | Zulfikar Laode | [暖金曲面](https://unsplash.com/photos/abstract-smooth-yellow-and-orange-curves-LxcVtiEW5dc) |
| `public/theme-wallpapers/deep-blue.webp` | Paul Sawyer | [深蓝波面](https://unsplash.com/photos/abstract-blue-waves-on-a-dark-background-OyYuvj4Dbek) |

## liqui-design

- 上游：[leefanv/liqui-design](https://github.com/leefanv/liqui-design)，固定 commit `fae9f2de55d8a049d77bbaed80f46fa68ae92508`。
- 上游文件：`packages/glass/src/LiquiGlass.tsx` 的曲面高度、Snell 折射查找表及法线高光；`packages/glass/src/glass.css` 的背景、着色、高光、内容分层思路。
- 许可证：MIT，Copyright (c) 2026 Fan Li。
- 项目落点：`src/composables/liquidGlass.ts`、`src/components/common/LiquidGlassLayer.vue`、`src/styles/_glass.scss`。
- 适配：保留本项目的圆角矩形轮廓和 Vue / Element Plus 组件；折射与高光贴图统一限制分辨率并使用 16 项 LRU；移除 React、Tailwind 和全局滤镜注册依赖；使用 Teleport 将弹窗光学层挂到外壳，文字不参与滤镜。没有复制上游组件、品牌素材或背景图片。

```text
MIT License

Copyright (c) 2026 Fan Li

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## liquid-glass-react

- 上游：<https://github.com/rdev/liquid-glass-react>（npm `liquid-glass-react@1.1.1`，发布包 `dist/index.esm.js` 中的 `src/shader-utils.ts` 部分）
- 许可证：MIT，Copyright 2025 Max Rovensky
- 项目落点：`src/composables/liquidGlass.ts`
- 复用内容：圆角矩形 SDF（`roundedRectSDF`）与 `smoothStep` 衰减，用于生成边缘位移贴图；SVG `feImage` + `feDisplacementMap` 对 backdrop 合成层施加折射的做法。
- 修改：改写为 TypeScript 纯函数；保留圆角 SDF 和向内采样，原 smoothStep 位移强度已由上面的曲面折射查找表替换；不跟随鼠标、不逐帧重算；去掉色差三通道与 React 组件；由 `LiquidGlassLayer.vue` 在 Chromium 内核中渐进启用，其他浏览器、减少透明度、强制颜色和用户关闭时回退纯 CSS 材质。

```
MIT License

Copyright 2025 MAX ROVENSKY

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
associated documentation files (the "Software"), to deal in the Software without restriction,
including without limitation the rights to use, copy, modify, merge, publish, distribute,
sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or
substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT
NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES
OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
```
