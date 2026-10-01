# 卡牌资源（有意省略 / intentionally omitted）

本目录在本源码快照中**不包含** `cards.json`，也不包含卡牌 PNG 或 `faces/`。

## 为什么省略

卡牌名称、费用与美术资源的再分发状态不明确，且可能属于第三方权利人。
为避免版权与隐私风险，这些资源未随源码一起分发，它们也不属于本快照所声明的
可读源码范围。

## 影响

- Android 应用（`:app`）通过
  `sourceSets["main"].assets.srcDir(rootProject.file("../assets/cards"))`
  读取本目录，因此卡牌名称、费用与美术可能缺失或不完整。
- 只有在使用者自行放入**合法获得**的、与本应用兼容的卡牌元数据与美术资源后，
  卡牌显示才会完整。
- 本仓库既不提供、也不引导获取这些资源的途径。

## English

Card metadata (`cards.json`) and card art (PNGs / `faces/`) are intentionally
omitted from this snapshot for licensing and privacy reasons; their
redistribution status is unclear. The Android app reads this directory, so card
display may be incomplete until users supply legally obtained compatible
assets. No such assets are provided here.
