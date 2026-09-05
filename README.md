# BiliFix White

[![Download](https://img.shields.io/github/downloads/Xposed-Modules-Repo/com.xjw.bilifix.in/total)](https://github.com/xiaojiuwo233/BiliFix/releases/latest)
[![stars](https://img.shields.io/github/stars/xiaojiuwo233/BiliFix?label=Stars)](https://github.com/xiaojiuwo233/BiliFix)

哔哩哔哩国际版（白版）已经停更并下架一段时间，app内部分功能已失效。本模块目的是修复一些当前无法正常的功能，同时增加一些本地化功能，满足日常使用。

> 自用模块，写得比较烂，不保证其稳定性，欢迎各位大佬贡献！

### 如果本模块对你有所帮助，欢迎点个[Star](https://github.com/xiaojiuwo233/BiliFix)，十分感谢！
### [关于新版bilibili国际版的说明](https://github.com/xiaojiuwo233/BiliFix/issues/6)

## 兼容性

- 仅支持哔哩哔哩国际白版 `(com.bilibili.app.in)` 3.20.4
- Xposed API 101 或更高版本

## 功能

功能设置入口已集成进应用内设置

### 修复

- 修复新版专栏无法在国际版正常查看的问题
- 修复动态无法显示专栏投稿相关内容
- 修复分区页加载失败
- 修复粉丝与关注列表显示未登录
- 修复钱包页加载失败
- 修复完整用户主页
- 修复无法发送付费表情包

### 增强

- 为部分图片分享菜单 添加系统分享按钮
- 评论区和用户主页显示用户 IP 属地
- 获取由b站自动生成的视频字幕资源（自动生成与自动翻译）
- 评论AI翻译（实验性功能）

## 已知问题

- 新版专栏下 评论区图片无法正常显示（可进入评论详情查看）
- 部分用户首次进入专栏可能会提示错误，重进即可恢复
- 部分账户无法显示评论中的IP属地 [#16](https://github.com/xiaojiuwo233/BiliFix/issues/16)

## 反馈

如果你在使用过程中遇到问题，欢迎在[issue](https://github.com/xiaojiuwo233/BiliFix/issues/new)中提出。

注意，反馈前务必包含必要的复现步骤和**打开详细日志选项**后的日志，否则issue将会被关闭。

## 许可证

本项目以 [GNU Affero General Public License v3.0](LICENSE) 开源。第三方组件仍遵循各自许可证。
