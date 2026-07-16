# EMI Accelerator

EMI Accelerator 用于减少 EMI 重载时的卡顿：缓存 EMI 物品列表，并将耗时的搜索索引构建延后到后台执行。

## 功能

- 将 `EmiStackList.stacks` 缓存到本地；当模组列表哈希未变化时，后续重载可直接复用。
- EMI 重载完成后，在后台构建搜索索引，让游戏更早恢复响应。
- 为延迟搜索构建加入重载代次检查，避免旧结果覆盖新状态。
- 为 EMI 重载清理和后台搜索构建加入状态锁，避免并发读写 EMI 内部数据。
- 客户端关闭时会处理仍在运行的后台搜索构建，减少退出阶段刷错。
- 缓存写入改为先写临时文件，再替换旧缓存，降低坏缓存概率。
- 重载耗时记录写入 `config/emi-accelerator/reload-timings.json`。

## 指令

| 指令 | 说明 |
| --- | --- |
| `/emiacc status` | 查看缓存、重载和延迟搜索状态。 |
| `/emiacc enable true\|false` | 开启或关闭加速功能。 |
| `/emiacc chat true\|false` | 切换聊天提示。 |
| `/emiacc debug true\|false` | 切换调试日志。 |
| `/emiacc clear` | 删除缓存文件。 |
| `/emiacc reload` | 触发 EMI 重载。 |
| `/emiacc reload --force` | 清除缓存并强制完整重载 EMI。 |

## 配置

配置文件位于 `config/emi-accelerator/emi-accelerator.txt`。

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `acceleration.enabled` | `true` | 加速总开关。 |
| `cache.enabled` | `true` | 是否启用物品列表缓存。 |
| `cache.auto_clear_on_mod_change` | `true` | 模组列表变化时自动删除缓存。 |
| `cache.max_file_size_mb` | `50` | 可接受的缓存文件大小上限。 |
| `diagnostics.enabled` | `true` | 是否记录重载耗时。 |
| `search.deferred` | `true` | 是否延迟构建 EMI 搜索索引。 |
| `chat.hide_messages` | `false` | 是否隐藏本模组的聊天提示。 |
| `debug.enabled` | `false` | 是否输出额外调试日志。 |

## 说明

在大型整合包中，延迟搜索构建仍可能耗时数秒到十几秒。此期间 EMI 重载会先完成，搜索索引会在后台构建完成后自动刷新。

需要 EMI、NeoForge 和 Minecraft 1.21.1。
