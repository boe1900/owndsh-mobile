# res/
> L2 | 父级: ../CLAUDE.md

成员清单
drawable-nodpi/ic_launcher_foreground.png: 432px 透明白鲸前景，普通/主题图标和网页登录前台服务通知共用 alpha 轮廓，非透明内容位于 108dp 画布中央 60dp 圆形区域。
mipmap-anydpi-v26/ic_launcher.xml: Android 8+ 自适应入口，系统裁剪黑色背景，前景保留安全边距。
mipmap-anydpi-v33/ic_launcher.xml: Android 13+ 配置，增加同一白鲸轮廓的 monochrome 通道，由系统主题着色。
mipmap-mdpi/ic_launcher.png: 48px 黑底旧版图标回退。
mipmap-hdpi/ic_launcher.png: 72px 黑底旧版图标回退。
mipmap-xhdpi/ic_launcher.png: 96px 黑底旧版图标回退。
mipmap-xxhdpi/ic_launcher.png: 144px 黑底旧版图标回退。
mipmap-xxxhdpi/ic_launcher.png: 192px 黑底旧版图标回退。

源稿为 [OwnDsh Desktop assets/icon.png](https://github.com/boe1900/owndsh-desktop/blob/main/assets/icon.png)，SHA256 为 `fe5ecb3ceb5ecbc053c7e4ee971f0e0754a9e9b89cc9e05ff1adb307a7e72ed1`。前景去除黑色背景并缩放进安全圆，旧版位图从自适应画布中央 72dp 区域生成；修改品牌时同步替换所有密度资源。构建只编译 XML 与 PNG，地图不进入 APK。

[PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
