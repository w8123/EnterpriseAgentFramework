// 操作手册截图清单。name 就是 docs/images/manual/<name>.webp 的文件名。
// 每项 run() 把页面停在要拍的状态；返回 Locator 时只拍那个元素，返回对象时作为 page.screenshot 的参数。

import { adminShots } from './shots-admin.mjs'
import { businessShots } from './shots-business.mjs'
import { managerShots } from './shots-manager.mjs'
import { developerShots } from './shots-developer.mjs'

export const shots = [
  ...adminShots,
  ...businessShots,
  ...managerShots,
  ...developerShots,
]
