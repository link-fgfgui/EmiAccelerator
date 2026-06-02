# EMI Accelerator

加速 EMI 重载，消除搜索索引构建时的界面卡顿。

## 特性

- **物品列表缓存**：首次加载时将物品列表序列化到磁盘，后续进入游戏直接读取，大幅缩短重载时间。
- **搜索延迟构建**：搜索索引改为后台线程构建，界面不再卡死；构建完成后自动刷新搜索结果，无需手动操作。
- **模组变更检测**：检测到模组列表变化时自动清除缓存，确保数据一致性。
- **诊断计时**：记录各阶段耗时并输出到文件，便于性能分析。

## 指令

| 指令 | 效果 |
|------|------|
| `/emiacc status` | 查看当前缓存与配置状态 |
| `/emiacc enable true\|false` | 开启或关闭加速功能 |
| `/emiacc chat true\|false` | 切换聊天提示的显示与隐藏 |
| `/emiacc debug true\|false` | 切换调试日志输出 |
| `/emiacc clear` | 删除缓存文件，下次重载重建 |
| `/emiacc reload` | 触发 EMI 重新加载 |
| `/emiacc reload --force` | 清除缓存并强制全量重载 |

## 配置

配置文件位于 `config/emi-accelerator/emi-accelerator.txt`：

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `acceleration.enabled` | boolean | true | 加速总开关 |
| `cache.enabled` | boolean | true | 是否缓存物品列表 |
| `cache.auto_clear_on_mod_change` | boolean | true | 模组变化时自动清缓存 |
| `cache.max_file_size_mb` | int | 50 | 缓存文件大小上限（MB） |
| `diagnostics.enabled` | boolean | true | 是否记录阶段耗时 |
| `search.deferred` | boolean | true | 是否启用延迟搜索 |
| `chat.hide_messages` | boolean | false | 是否隐藏聊天提示 |
| `debug.enabled` | boolean | false | 是否输出调试日志 |

## 工作原理

1. EMI 触发资源重载。
2. 检查本地缓存是否有效（通过模组列表哈希校验）。
3. 缓存有效 → 从磁盘反序列化物品列表，跳过 EMI 全量重建流程。
4. 缓存无效 → 执行正常 EMI 加载流程，完成后异步写入缓存。
5. 搜索索引在后台线程构建，不阻塞主线程；就绪后自动刷新界面。

## 依赖

- [EMI](https://modrinth.com/mod/emi) 1.1.22+（必需）
- NeoForge 21.1.228+ / Minecraft 1.21.1
- 客户端专用，无需在服务端安装


## 许可

[GNU AGPL 3.0](LICENSE)
