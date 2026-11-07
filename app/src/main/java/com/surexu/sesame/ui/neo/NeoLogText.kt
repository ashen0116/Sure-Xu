package com.surexu.sesame.ui.neo

/**
 * 日志口语化：只影响"通俗视图"的显示，不改动模块写入的原始日志。
 * 原始内容仍可在技术视图查看/复制，排查问题不受影响。
 */
object NeoLogText {

    private val TAG_MAP = mapOf(
        "RUNTIME" to "运行",
        "SYSTEM" to "运行",
        "FOREST" to "森林",
        "FARM" to "庄园",
        "OTHER" to "其他",
        "GOLDENBEANS" to "金豆",
        "DEBUG" to "调试",
        "ERROR" to "错误"
    )

    /** 技术噪音特征：Hook 注入、类名初始化、包名、异常栈、接口报文 */
    private val TECH_HINTS = listOf(
        "ApplicationHook",
        "AlipayMiniMarkHelper",
        "Oauth2AuthCodeHelper",
        "load success",
        "com.eg.android.AlipayGphone",
        "hook ",
        "hook,",
        "初始化完成",
        "Mtop",
        "mtop",
        "RPC",
        "Exception",
        "java.",
        "at com.",
        "err:",
        "err,",
        "wua",
        "token="
    )

    fun tag(raw: String?): String {
        if (raw.isNullOrBlank()) {
            return "日志"
        }
        return TAG_MAP[raw] ?: raw
    }

    fun isTechnical(tag: String?, body: String): Boolean {
        if (tag == "DEBUG") {
            return true
        }
        return TECH_HINTS.any { body.contains(it, ignoreCase = true) }
    }

    /** 正文轻度口语化：仅替换机器可读的标识，业务文案原样保留（含 emoji） */
    fun body(text: String): String {
        return text.replace("com.eg.android.AlipayGphone", "支付宝")
    }
}
