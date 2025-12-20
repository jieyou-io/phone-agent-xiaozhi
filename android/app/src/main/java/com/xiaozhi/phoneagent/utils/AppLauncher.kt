package com.xiaozhi.phoneagent.utils

import android.content.Context

object AppLauncher {
    private val appPackageMap = mapOf(
        // 社交通讯
        "微信" to "com.tencent.mm",
        "QQ" to "com.tencent.mobileqq",
        "微博" to "com.sina.weibo",
        "钉钉" to "com.alibaba.android.rimet",
        "飞书" to "com.ss.android.lark",

        // 电商购物
        "淘宝" to "com.taobao.taobao",
        "京东" to "com.jingdong.app.mall",
        "拼多多" to "com.xunmeng.pinduoduo",
        "天猫" to "com.tmall.wireless",
        "闲鱼" to "com.taobao.idlefish",

        // 美食外卖
        "美团" to "com.sankuai.meituan",
        "饿了么" to "me.ele",
        "大众点评" to "com.dianping.v1",

        // 出行旅游
        "高德地图" to "com.autonavi.minimap",
        "百度地图" to "com.baidu.BaiduMap",
        "滴滴出行" to "com.sdu.didi.psnger",
        "携程" to "ctrip.android.view",
        "12306" to "com.MobileTicket",

        // 视频娱乐
        "抖音" to "com.ss.android.ugc.aweme",
        "bilibili" to "tv.danmaku.bili",
        "爱奇艺" to "com.qiyi.video",
        "优酷" to "com.youku.phone",
        "腾讯视频" to "com.tencent.qqlive",
        "快手" to "com.smile.gifmaker",

        // 音乐音频
        "网易云音乐" to "com.netease.cloudmusic",
        "QQ音乐" to "com.tencent.qqmusic",
        "喜马拉雅" to "com.ximalaya.ting.android",

        // 内容社区
        "小红书" to "com.xingin.xhs",
        "知乎" to "com.zhihu.android",
        "豆瓣" to "com.douban.frodo",
        "今日头条" to "com.ss.android.article.news",

        // 工具
        "支付宝" to "com.eg.android.AlipayGphone",
        "浏览器" to "com.android.browser",
        "Chrome" to "com.android.chrome",
        "相机" to "com.android.camera",
        "设置" to "com.android.settings",
        "文件管理" to "com.android.fileexplorer"
    )

    fun getPackageName(context: Context, appName: String): String? {
        val prefs = PrefsManager(context)
        // Check custom apps first
        prefs.customApps[appName]?.let { return it }
        // Then check default map
        return appPackageMap[appName]
    }

    fun isAppInstalled(context: Context, packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun getAllSupportedApps(context: Context): List<String> {
        val prefs = PrefsManager(context)
        val allApps = LinkedHashSet<String>()
        // Add custom apps first
        allApps.addAll(prefs.customApps.keys)
        // Add default apps
        allApps.addAll(appPackageMap.keys)
        return allApps.toList()
    }

    fun addCustomApp(context: Context, appName: String, packageName: String) {
        val prefs = PrefsManager(context)
        val current = prefs.customApps.toMutableMap()
        current[appName] = packageName
        prefs.customApps = current
    }

    fun removeCustomApp(context: Context, appName: String) {
        val prefs = PrefsManager(context)
        val current = prefs.customApps.toMutableMap()
        current.remove(appName)
        prefs.customApps = current
    }
}
