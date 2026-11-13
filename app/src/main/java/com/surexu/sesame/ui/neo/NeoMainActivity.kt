package com.surexu.sesame.ui.neo

import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.text.Editable
import android.text.TextWatcher
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import java.io.RandomAccessFile
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import com.fasterxml.jackson.databind.ObjectMapper
import com.surexu.sesame.R
import com.surexu.sesame.util.PermissionUtil
import com.surexu.sesame.data.AppConfig
import com.surexu.sesame.data.ConfigV2
import com.surexu.sesame.data.Model
import com.surexu.sesame.data.ModelGroup
import com.surexu.sesame.data.RunType
import com.surexu.sesame.data.ViewAppInfo
import com.surexu.sesame.entity.UserEntity
import com.surexu.sesame.util.FeatureStats
import com.surexu.sesame.util.FileUtil
import com.surexu.sesame.util.LanguageUtil
import com.surexu.sesame.util.Statistics
import com.surexu.sesame.util.StringUtil
import com.surexu.sesame.util.idMap.UserIdMap
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 原创拟态 UI 主界面（原生 XML View 实现）。
 *
 * 完整 APP 骨架：底部导航栏 + 四个页面（首页 / 功能 / 日志 / 设置）。
 * 设计语言：霜白基色 #F6F9FE + 深雾蓝灰阴影 + 霓虹青主色，
 * 凸起/凹陷全部由 drawable 光照层模拟（见 res/drawable/neu_*）。
 *
 * 功能覆盖与模块版一致：一级入口基础/森林/庄园/新村/运动/其他全部真实跳转；
 * 农场(ORCHARD)并入庄园页、会员(MEMBER)并入其他页，金豆记录在日志页"记录"标签。
 */
class NeoMainActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_UI = "sesame_ui_state"
        private const val KEY_LAST_SELECTED_USER = "last_selected_user_id"

        // ===== 激活探测（与旧版模块 UI 主页面逻辑对齐）=====
        /** 激活探测最多重试次数 */
        private const val MAX_RUN_TYPE_PROBE_TIMES = 5
        /** 激活探测重试间隔(毫秒) */
        private const val RUN_TYPE_PROBE_INTERVAL_MS = 3000L

        // ===== 首页实时刷新 =====
        /** 首页数据自动刷新间隔(毫秒)：2 秒拉一次磁盘快照，日志里数字涨了首页要跟着变 */
        private const val STATS_REFRESH_INTERVAL_MS = 2000L

        // ===== 日志页常量（与旧版模块 UI 日志页逻辑对齐） =====
        /** 日志自动刷新间隔(毫秒) */
        const val LOG_REFRESH_INTERVAL_MS = 1000L
        /** 日志文件尾部最多读取的字节数(1MB) */
        const val LOG_MAX_TAIL_BYTES = 1024 * 1024L
        /** 日志查看时最多展示的条目数 */
        const val LOG_MAX_ENTRIES = 500
        /** 单次渲染最多生成的日志卡片数（超出只显示最新部分，配合 View 复用避免卡顿） */
        const val LOG_MAX_RENDER = 200

        /**
         * 视频红包页直达入口。
         * <p>取自支付有礼「逛15s搞钱攻略视频」任务的 btnLinkUrl —— 该页面没有公开入口，
         * 底部"视频"tab 是新闻短视频，不是同一个页面。
         */
        private const val VIDEO_REWARD_URL = "alipays://platformapi/startapp?appId=77700199&startMultApp=YES&appClearTop=NO&chInfo=zhifuyouli_ALL_CATEGORY&page=%2Fpages%2Findex%2Findex%3Fscene%3DuserLine%26fromscenario%3Dzhuangyuan%26videoGroup%3DALL_CATEGORY"
    }

    override fun attachBaseContext(newBase: Context) {
        ThemeUtil.applyNightMode()
        var ctx = LanguageUtil.setLocal(newBase)
        val scale = ctx.getSharedPreferences(PREFS_UI, Context.MODE_PRIVATE)
            .getInt(NeoSystemActivity.KEY_UI_SCALE, 100)
        if (scale != 100 && scale >= 50 && scale <= 200) {
            val config = Configuration(ctx.resources.configuration)
            config.densityDpi = ctx.resources.displayMetrics.densityDpi * scale / 100
            ctx = ctx.createConfigurationContext(config)
        }
        super.attachBaseContext(ctx)
    }

    /** 四个页面容器：懒加载（首次切到该页才 inflate），避免冷启动四页全量 inflate 拖慢首帧。 */
    private val pages = arrayOfNulls<View>(4)
    private val pageInited = BooleanArray(4)
    private lateinit var contentFrame: FrameLayout
    private lateinit var navItems: List<LinearLayout>
    private lateinit var navIcons: List<ImageView>
    private lateinit var navLabels: List<TextView>

    private val REQUEST_IMPORT_BACKUP = 1001
    private val REQUEST_EXPORT_BACKUP = 1002
    private val REQUEST_NOTIFICATIONS = 1003

    /** 主界面是否处于前台：后台/二级页停留时不改账号选择，防止配置页保存串写账号 */
    private var resumed = false

    /** 支付宝回包带过来的真实登录账号：自动切号成功后首页状态行要跟着换 */
    private var hostUid: String = ""

    /** 底部「首页」上次点击时间，用来识别双击刷新 */
    private var lastHomeNavClickMs = 0L

    /** 点「执行」的时刻：比它更早的进度账本属于上一轮，不回灌到刚归零的界面 */
    private var lastProgressResetAt = 0L

    /** 立即备份选择位置保存时暂存待写入的配置内容。 */
    private var pendingBackupJson: String? = null

    /** 能量统计卡定时刷新（读 statistics.json 磁盘快照，注入进程落盘后自动拉新）。 */
    private val statsRefreshHandler = Handler(Looper.getMainLooper())
    private val statsRefreshRunnable = object : Runnable {
        override fun run() {
            bindEnergyStats()
            // 进度条也一起兜底读盘：广播丢了一条，2 秒内自己会追上
            loadRunProgressForUi()
            statsRefreshHandler.postDelayed(this, STATS_REFRESH_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.neo_activity_main)
        setupSystemBars()

        contentFrame = findViewById<FrameLayout>(R.id.neo_content)
        // 首帧只 inflate 首页；功能/日志/设置页首次切到时再懒加载（见 ensurePageLoaded），
        // 避免冷启动四页全量 inflate + 各页列表构建同步阻塞首帧
        val homePage = layoutInflater.inflate(R.layout.neo_page_home, contentFrame, false)
        pages[0] = homePage
        contentFrame.addView(homePage)
        pageInited[0] = true

        navItems = listOf(
            findViewById(R.id.neo_nav_home),
            findViewById(R.id.neo_nav_features),
            findViewById(R.id.neo_nav_logs),
            findViewById(R.id.neo_nav_settings),
        )
        navIcons = listOf(
            findViewById(R.id.neo_nav_home_icon),
            findViewById(R.id.neo_nav_features_icon),
            findViewById(R.id.neo_nav_logs_icon),
            findViewById(R.id.neo_nav_settings_icon),
        )
        navLabels = listOf(
            findViewById(R.id.neo_nav_home_label),
            findViewById(R.id.neo_nav_features_label),
            findViewById(R.id.neo_nav_logs_label),
            findViewById(R.id.neo_nav_settings_label),
        )

        navItems.forEachIndexed { index, item ->
            item.setOnClickListener {
                haptic(item)
                // 快速双击底部「首页」＝刷新首页信息，跟右上角「刷新」一个效果
                if (index == 0) {
                    val now = System.currentTimeMillis()
                    if (now - lastHomeNavClickMs < 400L) {
                        lastHomeNavClickMs = 0L
                        bindEnergyStats()
                        loadRunProgressForUi()
                        Toast.makeText(this@NeoMainActivity, "已刷新", Toast.LENGTH_SHORT).show()
                    } else {
                        lastHomeNavClickMs = now
                    }
                }
                switchPage(index)
            }
        }

        // 首页立即绑定（监听器+本地一言，轻量）；功能/日志/设置页列表构建改到各自页首次懒加载时执行
        bindHomeActions()

        // 激活状态：初始化运行类型快照并监听变化（模块版逻辑，独立版同样可被注入后广播通知）
        ViewAppInfo.init(applicationContext)
        ViewAppInfo.setRunTypeListener {
            runOnUiThread { updateEnergyStatus() }
        }
        ViewAppInfo.checkRunType()
        updateEnergyStatus()

        val intentFilter = IntentFilter()
        intentFilter.addAction("com.surexu.sesame.status")
        intentFilter.addAction("com.surexu.sesame.update")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, intentFilter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(statusReceiver, intentFilter)
        }

        switchPage(0)
        applyFloatNav()

        // 首次进入请求文件权限：桌面入口是拟态新 UI，旧版只在 MiuixMainActivity 里申请，
        // 新用户从新 UI 进入时不再弹授权，这里补上
        if (!PermissionUtil.checkFilePermissions(this)) {
            PermissionUtil.checkOrRequestFilePermissions(this)
        }
        // Android 13+ 通知权限：manifest 已声明，首次进入一并请求
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            if (granted != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_NOTIFICATIONS
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 主界面回到前台：允许状态广播把支付宝当前登录账号同步过来（原来从没置过 true，切号后界面一直不换）
        resumed = true
        // 回到前台时若仍为未加载，按模块版机制周期性发查询广播等待支付宝进程回包（不覆盖晚到的广播信号）
        if (RunType.DISABLE == ViewAppInfo.getRunType()) {
            runTypeProbeHandler.removeCallbacks(runTypeProbeRunnable)
            runTypeProbeTimes = 0
            sendRunTypeQueryBroadcast()
            runTypeProbeHandler.postDelayed(runTypeProbeRunnable, RUN_TYPE_PROBE_INTERVAL_MS)
        }
        // 系统界面设置可能已变更：即时应用悬浮底栏并刷新设置页副标题
        applyFloatNav()
        updateAccountSettingCard()
        updateSystemSettingSub()
        // 能量统计实时刷新：回到前台立即拉一次，并启动定时拉新（注入进程落盘 statistics.json 后自动同步）
        bindEnergyStats()
        statsRefreshHandler.removeCallbacks(statsRefreshRunnable)
        statsRefreshHandler.postDelayed(statsRefreshRunnable, STATS_REFRESH_INTERVAL_MS)
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        // 离开前台即停止状态轮询，避免后台无谓广播与泄漏（与模块版一致）
        runTypeProbeHandler.removeCallbacks(runTypeProbeRunnable)
        // 离开前台停止能量统计定时刷新
        statsRefreshHandler.removeCallbacks(statsRefreshRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopLogAutoRefresh()
        runTypeProbeHandler.removeCallbacks(runTypeProbeRunnable)
        // 2 秒轮询改成必须随页面销毁一起撤掉，否则界面关了还在后台刷盘
        statsRefreshHandler.removeCallbacks(statsRefreshRunnable)
        try {
            unregisterReceiver(statusReceiver)
        } catch (e: Exception) {
            // 未注册成功时忽略
        }
    }

    private fun setupSystemBars() {
        window.statusBarColor = getColor(R.color.neo_base)
        window.navigationBarColor = getColor(R.color.neo_base)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !ThemeUtil.isNightActive(window.decorView.context)
            isAppearanceLightNavigationBars = !ThemeUtil.isNightActive(window.decorView.context)
        }
    }

    /** 切换页面与底部导航选中态。 */
    private fun switchPage(index: Int) {
        val activeColor = ContextCompat.getColor(this, R.color.neo_blue)
        val idleColor = ContextCompat.getColor(this, R.color.neo_text_hint)

        ensurePageLoaded(index)
        for (i in 0 until 4) {
            pages[i]?.visibility = if (i == index) View.VISIBLE else View.GONE
        }
        navItems.forEachIndexed { i, item ->
            item.isSelected = i == index
            val color = if (i == index) activeColor else idleColor
            navIcons[i].setColorFilter(color)
            navLabels[i].setTextColor(color)
        }
        // 日志页常驻自动刷新：切到日志页先懒加载首屏日志再启动，离开停止
        if (index == 2) {
            ensureLogsLoaded()
            startLogAutoRefresh()
        } else {
            stopLogAutoRefresh()
        }
    }

    /** 懒加载：页面首次显示时 inflate 并构建列表内容（避免冷启动四页全量 inflate 拖慢首帧）。 */
    private fun ensurePageLoaded(index: Int) {
        if (pageInited[index]) return
        val page = when (index) {
            1 -> layoutInflater.inflate(R.layout.neo_page_features, contentFrame, false)
            2 -> layoutInflater.inflate(R.layout.neo_page_logs, contentFrame, false)
            3 -> layoutInflater.inflate(R.layout.neo_page_settings, contentFrame, false)
            else -> return
        }
        pages[index] = page
        contentFrame.addView(page)
        pageInited[index] = true
        when (index) {
            1 -> buildFeatureGrid()
            2 -> bindLogsActions()
            3 -> {
                bindSettingsActions()
            }
        }
    }

    private fun bindHomeActions() {
        bindHitokoto()
        bindEnergyStats()
        findViewById<View>(R.id.neo_home_ai_btn).setOnClickListener {
            startActivity(Intent(this, NeoAIChatActivity::class.java))
        }
        findViewById<View>(R.id.neo_home_star_btn).setOnClickListener {
            // 通知支付宝进程内的模块重载（与模块版设置页重启同款广播）
            sendBroadcast(Intent("com.eg.android.AlipayGphone.sesame.restart"))
            Toast.makeText(this, "已发送重启支付宝指令", Toast.LENGTH_SHORT).show()
        }
        findViewById<View>(R.id.neo_home_more_btn).setOnClickListener { showConfigMenu() }
        findViewById<View>(R.id.neo_home_refresh_btn).setOnClickListener {
            haptic(it)
            bindEnergyStats()
            Toast.makeText(this, "已刷新", Toast.LENGTH_SHORT).show()
        }
        findViewById<View>(R.id.neo_home_run_btn).setOnClickListener { runAllTasksNow() }
        findViewById<View>(R.id.neo_home_stop_btn).setOnClickListener { stopAllTasksNow() }
        // 视频红包页没有公开入口，单独给个首页按钮直达，方便每天手动点提现
        findViewById<View>(R.id.neo_home_video_btn).setOnClickListener {
            haptic(it)
            openVideoRewardPage()
        }
        bindHomeToggles()
        bindBootSilence()
        renderRunProgress()
        loadRunProgressForUi()
    }

    /** 执行规则说明：新版只认三种触发，旧的「启动静默」开关已不再拦任务 */
    private fun bindBootSilence() {
        refreshBootSilenceText()
    }

    private fun refreshBootSilenceText() {
        val hint = findViewById<TextView>(R.id.neo_home_silence_hint) ?: return
        hint.text = "其它情况一律不自动执行；点「停止」可随时中断本轮"
    }

    /** 立即跑一轮全部任务：发模块的 execute 广播，group=BASE 表示不分组、全跑 */
    private fun runAllTasksNow() {
        try {
            // 手动点执行：先把上一轮清单清掉、状态置为进行中，等注入进程写回新的账本
            runItems.clear()
            runPhase = "running"
            lastProgressResetAt = System.currentTimeMillis()
            renderRunProgress()
            val intent = Intent("com.eg.android.AlipayGphone.sesame.execute")
            intent.putExtra("group", ModelGroup.BASE.code)
            sendBroadcast(intent)
            Toast.makeText(this, "已通知支付宝立即执行一轮", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "执行失败：" + (e.message ?: ""), Toast.LENGTH_SHORT).show()
        }
    }

    /** 立即停止：停掉主链和所有正在跑的功能；定时链仍会排到下一个间隔/定点 */
    private fun stopAllTasksNow() {
        try {
            sendBroadcast(Intent("com.eg.android.AlipayGphone.sesame.stop"))
            Toast.makeText(this, "已通知支付宝停止全部任务", Toast.LENGTH_SHORT).show()
            loadRunProgressForUi()
        } catch (e: Exception) {
            Toast.makeText(this, "停止失败：" + (e.message ?: ""), Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 视频红包页没有公开入口（底部"视频"tab 是新闻短视频，不是这个），
     * 只能借支付有礼任务下发的 btnLinkUrl 直达，故在菜单里放一个快捷入口；
     * 满 8 次后页面右上角会出现「提现 X 元」，点一下即可，提现接口未抓到、只能人工点。
     */
    private fun openVideoRewardPage() {
        // 以前发广播让支付宝后台进程去开页面，Android 10+ 会静默拦掉后台启动 Activity，
        // 界面还无条件弹「正在打开」，看起来就是按了没反应。改成界面在前台自己拉起支付宝。
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(VIDEO_REWARD_URL))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.setPackage("com.eg.android.AlipayGphone")
            startActivity(intent)
            return
        } catch (e: Exception) {
            // 支付宝版本不认这个 alipays:// 直达地址时，退回打开支付宝首页，至少能把 App 拉起来
            try {
                val fallback = packageManager.getLaunchIntentForPackage("com.eg.android.AlipayGphone")
                if (fallback != null) {
                    fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(fallback)
                    Toast.makeText(this, "已打开支付宝，请进视频页点提现", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "没找到支付宝，无法打开", Toast.LENGTH_SHORT).show()
                }
            } catch (e2: Exception) {
                Toast.makeText(this, "打开失败：" + (e2.message ?: ""), Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 三点菜单：默认配置 / 清除配置（拟态卡片弹窗，与首页同套 UI 语言）。 */
    private fun showConfigMenu() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.neo_dialog_config_menu)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnCancelListener { }

        val width = (resources.displayMetrics.widthPixels - (64 * resources.displayMetrics.density).toInt())
        dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)

        dialog.findViewById<TextView>(R.id.neo_dialog_config_state).text =
            "当前正在使用" + currentConfigLabel(restoreSelectedAccount())

        dialog.findViewById<View>(R.id.neo_dialog_run).setOnClickListener {
            dialog.dismiss()
            runAllTasksNow()
        }
        dialog.findViewById<View>(R.id.neo_dialog_video).setOnClickListener {
            dialog.dismiss()
            openVideoRewardPage()
        }
        dialog.findViewById<View>(R.id.neo_dialog_enable_all).setOnClickListener {
            dialog.dismiss()
            setAllModelsEnabled(true)
        }
        dialog.findViewById<View>(R.id.neo_dialog_disable_all).setOnClickListener {
            dialog.dismiss()
            setAllModelsEnabled(false)
        }
        dialog.findViewById<View>(R.id.neo_dialog_save).setOnClickListener {
            dialog.dismiss()
            showSavePresetDialog()
        }
        dialog.findViewById<View>(R.id.neo_dialog_default).setOnClickListener {
            dialog.dismiss()
            showRestorePresetDialog()
        }
        dialog.findViewById<View>(R.id.neo_dialog_clear).setOnClickListener {
            dialog.dismiss()
            showClearPresetDialog()
        }
        dialog.findViewById<View>(R.id.neo_dialog_cancel).setOnClickListener {
            dialog.dismiss()
        }
        dialog.show()
    }

    /**
     * 一键开启/关闭全部功能：批量改每个模型的 enable 总开关后落盘，并通知支付宝进程重载，
     * 免去重装后逐个手点的麻烦（只是改总开关，不动各功能内部的子参数）。
     */
    private fun setAllModelsEnabled(enabled: Boolean) {
        try {
            val userId = restoreSelectedAccount()
            // ConfigV2 的 isInit/getModelFieldsMap 是包内可见，UI 进程这边走公开入口
            Model.initAllModel()
            ConfigV2.load(userId)
            var n = 0
            for (code in Model.getModelConfigMap().keys) {
                val field = ConfigV2.INSTANCE.getModelFields(code)?.get("enable") ?: continue
                field.setObjectValue(enabled)
                n++
            }
            val saved = ConfigV2.save(userId, true)
            // 通知支付宝进程内的模块重载，让开关立即生效
            sendBroadcast(Intent("com.eg.android.AlipayGphone.sesame.restart"))
            val verb = if (enabled) "开启" else "关闭"
            Toast.makeText(
                this,
                if (saved == true) "已${verb}全部 $n 个功能" else "${verb}失败：配置未保存",
                Toast.LENGTH_SHORT
            ).show()
        } catch (e: Exception) {
            Toast.makeText(this, "操作失败：" + (e.message ?: ""), Toast.LENGTH_SHORT).show()
        }
    }

    /** 保存当前配置：先选位置（配置1~5），再把当前配置文件整份复制过去 */
    private fun showSavePresetDialog() {
        showOptionDialog("保存到哪个位置", (1..5).map { "配置$it" }) { index ->
            val slot = index + 1
            if (savePresetConfig(slot)) {
                Toast.makeText(this, "已保存到配置$slot", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "保存失败：读不到当前配置", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 恢复配置：系统默认值 + 5 个保存位置，选哪个恢复哪个 */
    private fun showRestorePresetDialog() {
        val options = listOf("系统默认值") + (1..5).map { "配置$it" }
        showOptionDialog("恢复到哪个配置", options) { index ->
            if (index == 0) {
                restoreDefaultConfig()
                return@showOptionDialog
            }
            if (restorePresetConfig(index)) {
                Toast.makeText(this, "已恢复到配置$index", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "配置$index 还没有保存过", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 当前账号实际使用的配置文件；userId 为空时是公共默认配置 */
    private fun activeConfigFile(userId: String?): File =
        if (userId.isNullOrEmpty()) FileUtil.getDefaultConfigV2File() else FileUtil.getConfigV2File(userId)

    /** 存档文件：和正式配置同目录，按账号分开，名字是 preset_1.json ~ preset_5.json */
    private fun presetConfigFile(userId: String?, slot: Int): File =
        File(activeConfigFile(userId).parentFile, "preset_$slot.json")

    /**
     * 判断当前这份配置是从哪来的：
     * 先和 5 个存档逐份比内容，一样就显示「配置N」；
     * 再逐项和代码里的默认值比，全都一样就是「系统默认配置」；否则「自定义配置」。
     */
    private fun currentConfigLabel(userId: String?): String {
        return try {
            val cur = activeConfigFile(userId)
            if (!cur.exists()) return "系统默认配置"
            val text = cur.readText()
            for (slot in 1..5) {
                val f = presetConfigFile(userId, slot)
                if (f.exists() && f.readText() == text) return "配置$slot"
            }
            Model.initAllModel()
            ConfigV2.load(userId)
            var allDefault = true
            outer@ for (code in Model.getModelConfigMap().keys) {
                val fields = ConfigV2.INSTANCE.getModelFields(code) ?: continue
                for (field in fields.values) {
                    if (!field.isDefaultValue) {
                        allDefault = false
                        break@outer
                    }
                }
            }
            if (allDefault) "系统默认配置" else "自定义配置"
        } catch (e: Exception) {
            "自定义配置"
        }
    }

    /** 清除配置：可以只清某个存档，也可以清全部 */
    private fun showClearPresetDialog() {
        val options = listOf("全部配置") + (1..5).map { "配置$it" }
        showOptionDialog("清除哪个配置", options) { index ->
            if (index == 0) {
                clearConfig()
                return@showOptionDialog
            }
            val slot = index
            if (clearPresetConfig(slot)) {
                Toast.makeText(this, "已清除配置$slot", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "配置$slot 本来就是空的", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 删掉第 slot 个存档（1~5），只动存档，不碰当前正在用的配置 */
    private fun clearPresetConfig(slot: Int): Boolean {
        return try {
            val file = presetConfigFile(restoreSelectedAccount(), slot)
            file.exists() && file.delete()
        } catch (e: Exception) {
            false
        }
    }

    /** 把当前配置存进第 slot 个位置（1~5），已有内容直接覆盖 */
    private fun savePresetConfig(slot: Int): Boolean {
        return try {
            val userId = restoreSelectedAccount()
            Model.initAllModel()
            ConfigV2.load(userId)
            // 先把内存里尚未落盘的改动写进正式配置，保证存下来的是界面上看到的这一份
            ConfigV2.save(userId, true)
            val src = activeConfigFile(userId)
            if (!src.exists()) return false
            val dst = presetConfigFile(userId, slot)
            dst.parentFile?.mkdirs()
            dst.writeText(src.readText())
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 从第 slot 个位置恢复（1~5）；覆盖前先滚动备份当前配置 */
    private fun restorePresetConfig(slot: Int): Boolean {
        return try {
            val userId = restoreSelectedAccount()
            val src = presetConfigFile(userId, slot)
            if (!src.exists()) return false
            val dst = activeConfigFile(userId)
            FileUtil.backupConfigV2WithRolling(userId)
            dst.parentFile?.mkdirs()
            dst.writeText(src.readText())
            ConfigV2.unload()
            ConfigV2.load(userId)
            sendBroadcast(Intent("com.eg.android.AlipayGphone.sesame.restart"))
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 恢复默认值：把全部功能项的所有字段重置为代码默认值并保存。
     * 不删配置文件、不删备份（旧的“删文件重建”做法会连带清掉账号维度配置与滚动备份），
     * 需要彻底清掉时用下面的「清除配置」。
     */
    private fun restoreDefaultConfig() {
        try {
            val userId = restoreSelectedAccount()
            Model.initAllModel()
            ConfigV2.load(userId)
            var n = 0
            for (code in Model.getModelConfigMap().keys) {
                val modelFields = ConfigV2.INSTANCE.getModelFields(code) ?: continue
                for (field in modelFields.values) {
                    field.reset()
                    n++
                }
            }
            val saved = ConfigV2.save(userId, true)
            sendBroadcast(Intent("com.eg.android.AlipayGphone.sesame.restart"))
            Toast.makeText(
                this,
                if (saved == true) "已恢复默认值（$n 项）" else "恢复失败：配置未保存",
                Toast.LENGTH_SHORT
            ).show()
        } catch (e: Exception) {
            Toast.makeText(this, "恢复默认值失败：" + (e.message ?: ""), Toast.LENGTH_SHORT).show()
        }
    }

    /** 清除配置：删除默认配置文件与全部滚动备份，下次加载自动重建默认。 */
    private fun clearConfig() {
        try {
            val file = FileUtil.getDefaultConfigV2File()
            if (file.exists()) file.delete()
            val backupDir = FileUtil.getBackupDirectoryFile()
            backupDir.listFiles()?.forEach { it.delete() }
            ConfigV2.unload()
            // 清除配置只重新初始化、不触发任务执行（reinit 广播）
            sendBroadcast(Intent("com.eg.android.AlipayGphone.sesame.reinit"))
            Toast.makeText(this, "已清除配置", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "清除配置失败", Toast.LENGTH_SHORT).show()
        }
    }

    /** 首页统计首次读盘标记：避免冷启动首帧被 statistics.json 读盘+格式化写盘阻塞。 */
    private val statsLoadedOnce = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 当前时间档位：今日/本月/今年/累计，森林卡与各功能卡共用 */
    private var statPeriod = Statistics.TimeType.DAY

    private class FeatureMetric(val key: String, val label: String)

    private class FeatureCardSpec(
        val name: String,
        val icon: String,
        /** FeatureStats 里的功能名（与模型 getName 一致） */
        val model: String,
        val metrics: List<FeatureMetric>
    )

    private val featureCardSpecs = listOf(
        FeatureCardSpec("小鸡", "🐣", "庄园", listOf(
            FeatureMetric("egg", "收蛋 颗"), FeatureMetric("donate", "捐蛋 颗"), FeatureMetric("feed", "喂食 g"))),
        FeatureCardSpec("海洋", "🌊", "海洋", listOf(
            FeatureMetric("energy", "能量 g"), FeatureMetric("fish", "摸鱼 次"), FeatureMetric("puzzle", "拼图 块"))),
        FeatureCardSpec("新村", "🏘", "新村", listOf(
            FeatureMetric("coin", "木兰币"), FeatureMetric("ticket", "贴罚单"), FeatureMetric("booth", "摆摊 次"))),
        FeatureCardSpec("芭芭农场", "🌳", "农场", listOf(
            FeatureMetric("fertilizer", "肥料 g"), FeatureMetric("spread", "浇肥 次"),
            FeatureMetric("gain", "今日得肥 g"))),
        FeatureCardSpec("花呗金", "💳", "花呗金", listOf(
            FeatureMetric("sign", "签到 次"), FeatureMetric("gold", "花呗金 个"),
            FeatureMetric("hhk", "花花卡 张")))
    )

    private fun colorOf(id: Int): Int = androidx.core.content.ContextCompat.getColor(this, id)

    /** 首页仪表盘：森林能量 + 各功能统计，数据来自 statistics.json / feature_stats.json 快照 */
    private fun bindEnergyStats() {
        if (!pageInited[0]) return
        // 独立 App 进程不随支付宝注入，需读磁盘快照；首次放后台线程，避免首帧卡住
        if (statsLoadedOnce.compareAndSet(false, true)) {
            Thread {
                try {
                    Statistics.loadForUi()
                } catch (th: Throwable) {
                    // 读盘失败保留占位值
                }
                try {
                    FeatureStats.reload()
                } catch (th: Throwable) {
                }
                runOnUiThread { fillHomeStats() }
            }.start()
        } else {
            // 每次刷新都重读「当前账号」的统计快照：原来只在首次读一次，
            // 所以日志里数字涨了首页还停在老数字；也避免界面进程反过来写统计文件
            try {
                Statistics.loadForUi()
            } catch (th: Throwable) {
            }
            try {
                FeatureStats.reload()
            } catch (th: Throwable) {
            }
            fillHomeStats()
        }
        // 静默倒计时跟着首页 2 秒刷新一起走
        refreshBootSilenceText()
    }

    private fun rangeLabel(tt: Statistics.TimeType): String = when (tt) {
        Statistics.TimeType.DAY -> "今日"
        Statistics.TimeType.MONTH -> "本月"
        Statistics.TimeType.YEAR -> "今年"
        else -> "累计"
    }

    private fun statPeriodOf(tt: Statistics.TimeType): FeatureStats.Period = when (tt) {
        Statistics.TimeType.DAY -> FeatureStats.Period.DAY
        Statistics.TimeType.MONTH -> FeatureStats.Period.MONTH
        Statistics.TimeType.YEAR -> FeatureStats.Period.YEAR
        else -> FeatureStats.Period.ALL
    }

    private fun fmtNumber(v: Double): String =
        if (Math.abs(v - Math.round(v)) < 0.05) String.format("%,d", Math.round(v))
        else String.format("%,.1f", v)

    /** 首页列表上次重绘时的数据指纹：2 秒刷新只在数据真变了才重建卡片，避免反复 addView 卡顿 */
    private var lastHomeDataKey: String = ""

    /** 「其他功能」要展示的子功能清单：常见功能 + 所有跑过、埋过点名的功能 */
    private fun otherFeatureNames(): List<String> {
        val names = LinkedHashSet<String>()
        names.addAll(listOf("游戏中心", "消费金", "百次立减", "花呗金", "内容种草滑动红包",
                "农场", "新村", "运动", "会员", "古树", "保护", "物种", "森林", "庄园"))
        try {
            names.addAll(FeatureStats.allFeatureNames())
        } catch (th: Throwable) {
        }
        return names.toList()
    }

    private fun homeDataFingerprint(tt: Statistics.TimeType): String {
        val sb = StringBuilder().append(tt.ordinal).append('|')
                .append(Statistics.getData(tt, Statistics.DataType.COLLECTED)).append('|')
                .append(Statistics.getRoundCollected()).append('|')
        val period = statPeriodOf(tt)
        for (spec in featureCardSpecs) {
            sb.append(FeatureStats.lastRun(spec.model)).append(':')
            for (m in spec.metrics) {
                sb.append(FeatureStats.get(spec.model, m.key, period)).append(',')
            }
            sb.append('|')
        }
        for (name in otherFeatureNames()) {
            sb.append(name).append(':')
                    .append(FeatureStats.lastRun(name)).append(':')
                    .append(FeatureStats.status(name)).append(':')
                    .append(FeatureStats.todayPoints(name)).append('|')
        }
        // 「中途断开」按起跑超过 10 分钟判断，时间也要参与指纹，否则过了 10 分钟界面不更新
        sb.append(System.currentTimeMillis() / 60000L)
        return sb.toString()
    }

    private fun fillHomeStats() {
        val tt = statPeriod
        fun stat(id: Int, d: Statistics.DataType) {
            findViewById<TextView>(id).text = String.format("%,d", Statistics.getData(tt, d))
        }

        findViewById<TextView>(R.id.neo_home_forest_title).text = "森林 · ${rangeLabel(tt)}收取"
        val collected = Statistics.getData(tt, Statistics.DataType.COLLECTED)
        findViewById<TextView>(R.id.neo_home_forest_value).text = String.format("%,d", collected)
        val cmp = listOf(Statistics.TimeType.MONTH, Statistics.TimeType.YEAR, Statistics.TimeType.ALL)
            .filter { it != tt }
            .joinToString("\n") { "${rangeLabel(it)} ${String.format("%,d", Statistics.getData(it, Statistics.DataType.COLLECTED))}" }
        findViewById<TextView>(R.id.neo_home_forest_cmp).text = cmp
        stat(R.id.neo_home_tile_help, Statistics.DataType.HELPED)
        stat(R.id.neo_home_tile_water, Statistics.DataType.WATERED)
        stat(R.id.neo_home_tile_watered, Statistics.DataType.WATEREDCOUNT)
        stat(R.id.neo_home_tile_watering, Statistics.DataType.WATERINGCOUNT)

        // 「本期占累计」和那根进度条没有实际意义，已从布局里删掉，这里只留本轮已收
        findViewById<TextView>(R.id.neo_home_forest_round).text = "本轮已收 ${Statistics.getRoundCollected()} g"

        val tabs = listOf(
            Statistics.TimeType.DAY to R.id.neo_home_range_day,
            Statistics.TimeType.MONTH to R.id.neo_home_range_month,
            Statistics.TimeType.YEAR to R.id.neo_home_range_year,
            Statistics.TimeType.ALL to R.id.neo_home_range_all
        )
        for ((t, id) in tabs) {
            val tv = findViewById<TextView>(id)
            val selected = t == tt
            tv.setBackgroundResource(if (selected) R.drawable.neo_home_range_on else 0)
            tv.setTextColor(colorOf(if (selected) R.color.neo_primary else R.color.neo_text_hint))
            tv.setOnClickListener {
                statPeriod = t
                fillHomeStats()
            }
        }

        val dataKey = homeDataFingerprint(tt)
        if (dataKey != lastHomeDataKey) {
            lastHomeDataKey = dataKey
            fillFeatureCards(tt)
            fillMoreFeatures()
        }
        fillSwitchCard()
        updateEnergyStatus()
    }

    /** 各功能卡：每个功能一行，显示当前档位的 2~3 个指标 */
    private fun fillFeatureCards(tt: Statistics.TimeType) {
        val box = findViewById<LinearLayout>(R.id.neo_home_feature_container)
        box.removeAllViews()
        findViewById<TextView>(R.id.neo_home_feature_title).text = "各功能 · ${rangeLabel(tt)}"
        val period = statPeriodOf(tt)
        for (spec in featureCardSpecs) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.neu_card_raised)
                setPadding(dp(13), dp(10), dp(13), dp(10))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) }
            }

            val head = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            val name = TextView(this).apply {
                text = "${spec.icon} ${spec.name}"
                textSize = 13f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(colorOf(R.color.neo_text_primary))
            }
            head.addView(name, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val last = try {
                FeatureStats.lastRun(spec.model)
            } catch (th: Throwable) {
                0L
            }
            val lastText = TextView(this).apply {
                text = if (last > 0) "上次 " + java.text.SimpleDateFormat("HH:mm", java.util.Locale.ROOT).format(java.util.Date(last)) else "上次 —"
                textSize = 10f
                setTextColor(colorOf(R.color.neo_text_hint))
            }
            head.addView(lastText)
            card.addView(head)

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(10), 0, 0)
            }
            spec.metrics.forEachIndexed { mi, m ->
                val cell = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundResource(R.drawable.neu_card_pressed)
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                }
                val value = try {
                    fmtNumber(FeatureStats.get(spec.model, m.key, period))
                } catch (th: Throwable) {
                    "0"
                }
                cell.addView(TextView(this).apply {
                    text = value
                    textSize = 16f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setTextColor(colorOf(R.color.neo_text_primary))
                })
                cell.addView(TextView(this).apply {
                    text = m.label
                    textSize = 10f
                    setTextColor(colorOf(R.color.neo_text_hint))
                })
                row.addView(cell, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = if (mi < spec.metrics.lastIndex) dp(8) else 0
                })
            }
            card.addView(row)
            box.addView(card)
        }
    }

    /** 其他功能：列出所有子功能，显示今日点数和「今日已跑 / 中途断开」等状态 */
    private fun fillMoreFeatures() {
        val box = findViewById<LinearLayout>(R.id.neo_home_more_container)
        box.removeAllViews()
        val list = otherFeatureNames()
        val todayKey = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.ROOT).format(java.util.Date())
        list.forEachIndexed { index, modelName ->
            val last = try {
                FeatureStats.lastRun(modelName)
            } catch (th: Throwable) {
                0L
            }
            val ranToday = last > 0 && java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.ROOT).format(java.util.Date(last)) == todayKey
            val status = try {
                FeatureStats.status(modelName)
            } catch (th: Throwable) {
                ""
            }
            val points = try {
                FeatureStats.todayPoints(modelName)
            } catch (th: Throwable) {
                0.0
            }
            // 起跑后超过 10 分钟还没写收尾状态，就是中途断开（进程被杀 / 支付宝重启）
            val stateText = when {
                !ranToday -> "今天未跑"
                status == "running" && System.currentTimeMillis() - last > 10 * 60 * 1000L -> "中途断开"
                status == "partial" -> "部分完成"
                status == "failed" -> "执行异常"
                else -> "今日已跑"
            }
            val stateColor = if (stateText == "中途断开" || stateText == "执行异常")
                R.color.neo_text_hint else R.color.neo_primary
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, dp(10))
            }
            row.addView(TextView(this).apply {
                text = modelName
                textSize = 12f
                setTextColor(colorOf(R.color.neo_text_secondary))
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(this).apply {
                text = if (points > 0) "${fmtNumber(points)}点" else "—"
                textSize = 11f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(colorOf(R.color.neo_text_primary))
            })
            row.addView(TextView(this).apply {
                text = stateText
                textSize = 10f
                setTextColor(colorOf(stateColor))
                setPadding(dp(8), 0, 0, 0)
            })
            box.addView(row)
            if (index < list.size - 1) {
                box.addView(View(this).apply {
                    setBackgroundColor(android.graphics.Color.parseColor("#F1F5FB"))
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))
            }
        }
    }

    /** 自动切号：直接读注入进程写的两个状态文件，不依赖 libxposed 类 */
    private fun fillSwitchCard() {
        try {
            val settingsFile = java.io.File(FileUtil.MAIN_DIRECTORY_FILE, "account_switch_settings.json")
            var enabled = false
            var intervalSeconds = 0
            if (settingsFile.isFile() && settingsFile.length() <= 16384) {
                val jo = org.json.JSONObject(FileUtil.readFromFile(settingsFile))
                enabled = jo.optBoolean("enabled", false)
                intervalSeconds = jo.optInt("intervalSeconds", 0)
            }
            findViewById<TextView>(R.id.neo_home_switch_status).text = if (enabled) "已开启" else "未开启"
            findViewById<TextView>(R.id.neo_home_switch_interval).text =
                if (intervalSeconds > 0) "${intervalSeconds / 60} 分钟" else "—"

            val statusFile = java.io.File(FileUtil.MAIN_DIRECTORY_FILE, "account_switch_status.json")
            val now = System.currentTimeMillis()
            val clock = java.text.SimpleDateFormat("HH:mm", java.util.Locale.ROOT)
            var phaseText = "—"
            var nextText = "—"
            if (statusFile.isFile && statusFile.length() <= 512) {
                val jo = org.json.JSONObject(FileUtil.readFromFile(statusFile))
                val phase = jo.optString("phase", "")
                val updatedAt = jo.optLong("updatedAt", 0L)
                val fresh = now - updatedAt <= 90000L
                phaseText = when {
                    !enabled -> "未开启自动切号"
                    phase.isEmpty() || !fresh -> "等待支付宝更新"
                    else -> switchPhaseText(phase)
                }
                val nextAt = jo.optLong("nextAt", 0L)
                nextText = when {
                    !enabled -> "—"
                    // 已经进倒计时：直接显示算出来的准确钟点
                    nextAt > now && fresh -> clock.format(java.util.Date(nextAt))
                    nextAt in 1..now && fresh -> "即将切换"
                    // 还没进倒计时（等回首页 / 等本轮跑完）：也给出时间点，别再只写「等回到首页」
                    intervalSeconds > 0 -> "预计 " + clock.format(java.util.Date(now + intervalSeconds * 1000L))
                    else -> "—"
                }
            } else if (enabled && intervalSeconds > 0) {
                nextText = "预计 " + clock.format(java.util.Date(now + intervalSeconds * 1000L))
            }
            findViewById<TextView>(R.id.neo_home_switch_phase).text = phaseText
            findViewById<TextView>(R.id.neo_home_switch_next).text = nextText
        } catch (th: Throwable) {
            // 文件不存在或解析失败时保持占位
        }
    }

    /** 把切号内部状态翻成大白话，别再显示 WAIT_HOME、「等待中」这种看不懂的字 */
    private fun switchPhaseText(phase: String): String = when (phase) {
        "WAIT_HOST" -> "等支付宝服务就绪"
        "WAIT_IDENTITY" -> "等账号信息同步"
        "WAIT_HISTORY" -> "等凑齐两个可切换账号"
        "WAIT_HOME" -> "等回到支付宝首页（二级页面不切）"
        "WAIT_CAPTCHA" -> "等验证码处理完"
        "WAIT_TASKS" -> "等本轮任务跑完"
        "WAIT_IDLE" -> "等本轮空闲"
        "COUNTDOWN" -> "倒计时中，马上就切"
        "ROUND_COOLDOWN" -> "刚切回首个账号，冷却中"
        "SWITCHING" -> "正在切换账号"
        "CONFIRMING" -> "正在确认切换结果"
        "PAUSED" -> "切号暂停了，关掉再开可恢复"
        "DISABLED" -> "已关闭"
        else -> "倒计时中"
    }

    /** 注入状态广播：模块被 LSPosed 启用并注入支付宝后标记已激活；update 广播同步刷新统计。 */
    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                "com.surexu.sesame.status" -> {
                    // 兼容两套回包：新版带 runType extra（禁用=0/激活=1），旧版无 extra 默认视为已激活
                    val code = intent.getIntExtra("runType", -1)
                    if (code >= 0) {
                        ViewAppInfo.setRunTypeByCode(code)
                    } else {
                        ViewAppInfo.setRunTypeByCode(RunType.MODEL.getCode())
                    }
                    // 支付宝真正登录的账号（自动切号成功后会变）：记下来给首页状态行显示
                    val uid = intent.getStringExtra("uid")
                    if (!uid.isNullOrEmpty()) {
                        hostUid = uid
                        // 配置跟随登录账号：仅主界面在前台时才同步，防止配置页保存串写成另一账号
                        if (code == RunType.MODEL.getCode() && resumed && uid != restoreSelectedAccount()) {
                            uiPrefs.edit().putString(KEY_LAST_SELECTED_USER, uid).apply()
                            runOnUiThread { updateAccountSettingCard() }
                        }
                    }
                    runTypeProbeTimes = 0
                    runTypeProbeHandler.removeCallbacks(runTypeProbeRunnable)
                    updateEnergyStatus()
                }

                "com.surexu.sesame.update" -> {
                    val event = intent.getStringExtra("progressEvent")
                    if (!event.isNullOrEmpty()) {
                        // 进度以磁盘账本为准，广播只负责喊一声「快去读」，丢一条也不影响
                        runOnUiThread { loadRunProgressForUi() }
                    } else {
                        runOnUiThread { bindEnergyStats() }
                    }
                }
            }
        }
    }

    /** 向支付宝进程查询模块注入状态：旧版模块 UI 主页面同款动作 */
    private fun sendRunTypeQueryBroadcast() {
        try {
            sendBroadcast(Intent("com.eg.android.AlipayGphone.sesame.status"))
        } catch (th: Throwable) {
            Log.i("view", "sendBroadcast status err:", th)
        }
    }

    /** 本轮清单里的一项：功能名 + 状态 */
    private data class TaskItem(val name: String, val status: String)

    /** idle / running / done / stopped / stalled */
    private var runPhase = "idle"
    /** 下一次执行的时间与方式，如 "17:53#定点" */
    private var runNext = ""
    private val runItems = mutableListOf<TaskItem>()

    /** 读注入进程写的进度账本：整轮状态、本轮清单、每一项状态、下次执行时间 */
    private fun loadRunProgressForUi() {
        try {
            val f = java.io.File(FileUtil.MAIN_DIRECTORY_FILE, "run_progress.json")
            if (!f.isFile || f.length() > 65536) return
            val jo = org.json.JSONObject(FileUtil.readFromFile(f))
            val updatedAt = jo.optLong("updatedAt", 0L)
            if (updatedAt < lastProgressResetAt) return
            // 超过 3 分钟没有新事件，别让「正在执行」永远挂着
            val stalled = System.currentTimeMillis() - updatedAt > 3 * 60 * 1000L
            val phase = jo.optString("phase", "idle")
            runPhase = if (stalled && phase == "running") "stalled" else phase
            runNext = jo.optString("nextRun", "")
            runItems.clear()
            val arr = jo.optJSONArray("items")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val n = o.optString("name", "")
                    if (n.isNotBlank()) runItems.add(TaskItem(n, o.optString("status", "")))
                }
            }
            renderRunProgress()
        } catch (th: Throwable) {
        }
    }

    /** 参考图里的粉彩小色块：圆角底 + 对应深色字 */
    private fun pastelText(text: String, bgRes: Int, fgRes: Int, radiusDp: Int = 10): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 10f
            setTextColor(colorOf(fgRes))
            val d = android.graphics.drawable.GradientDrawable()
            d.cornerRadius = dp(radiusDp).toFloat()
            d.setColor(colorOf(bgRes))
            background = d
            setPadding(dp(9), dp(4), dp(9), dp(4))
        }
    }

    /** 首页进度：状态药丸 + 一根总进度条 + 当前项 + 本轮清单 */
    private fun renderRunProgress() {
        if (!pageInited[0]) return
        val box = findViewById<LinearLayout>(R.id.neo_home_progress_container) ?: return
        box.removeAllViews()
        val total = runItems.size
        val done = runItems.count { it.status == "完成" || it.status == "跳过" }
        val (label, chipBg, chipFg) = when (runPhase) {
            "running" -> Triple("正在执行", R.color.neo_pastel_purple, R.color.neo_pastel_purple_text)
            "done" -> Triple("本轮已完成", R.color.neo_pastel_mint, R.color.neo_pastel_mint_text)
            "stopped" -> Triple("已停止", R.color.neo_pastel_rose, R.color.neo_pastel_rose_text)
            "stalled" -> Triple("可能已中断", R.color.neo_pastel_amber, R.color.neo_pastel_amber_text)
            else -> Triple("空闲", R.color.neo_pressed, R.color.neo_text_secondary)
        }
        findViewById<TextView>(R.id.neo_home_progress_title)?.text = "执行进度"

        // 状态药丸 + 完成数，一行看清「在不在跑、跑到第几个」
        val statusLine = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        statusLine.addView(pastelText(label, chipBg, chipFg, 100))
        statusLine.addView(TextView(this).apply {
            text = if (total > 0) "  $done / $total 项" else ""
            textSize = 12f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(colorOf(R.color.neo_text_primary))
        })
        box.addView(statusLine)

        val running = runItems.firstOrNull { it.status == "进行中" }?.name.orEmpty()
        val summary = if (runPhase == "running" && running.isNotBlank()) {
            "当前正在跑：$running"
        } else {
            when (runPhase) {
                "done" -> "本轮跑完 $total 项"
                "stopped" -> "已手动停止"
                "stalled" -> "超过 3 分钟没有新进展，可在日志里确认"
                else -> "现在没有任务在跑"
            }
        }
        box.addView(TextView(this).apply {
            text = summary
            textSize = 11f
            setTextColor(colorOf(R.color.neo_text_secondary))
            setPadding(0, dp(5), 0, dp(2))
        })

        // 单根总进度条
        val pct = if (total <= 0) 0 else (done * 100 / total).coerceIn(0, 100)
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundResource(R.drawable.neo_home_track)
        }
        val fillWeight = pct.coerceAtLeast(1).toFloat() / 100f
        bar.addView(View(this).apply { setBackgroundResource(R.drawable.neo_home_track_fill) },
            LinearLayout.LayoutParams(0, dp(8), fillWeight))
        bar.addView(View(this), LinearLayout.LayoutParams(0, dp(8), 1f - fillWeight))
        box.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(8)).apply {
            topMargin = dp(6)
            bottomMargin = dp(6)
        })

        if (runNext.isNotBlank()) {
            val parts = runNext.split("#")
            box.addView(
                pastelText(
                    "下一次执行 ${parts.getOrNull(0).orEmpty()}（${parts.getOrNull(1).orEmpty()}）",
                    R.color.neo_pastel_blue, R.color.neo_pastel_blue_text
                ),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(2) }
            )
        }

        if (total > 0) {
            box.addView(TextView(this).apply {
                text = "本轮项目"
                textSize = 10f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(colorOf(R.color.neo_text_secondary))
                setPadding(0, dp(4), 0, dp(2))
            })
            val limit = 24
            for (item in runItems.take(limit)) {
                box.addView(TextView(this).apply {
                    text = when (item.status) {
                        "进行中" -> "▶ ${item.name}"
                        "完成" -> "✓ ${item.name}"
                        "跳过" -> "— ${item.name}（条件没到）"
                        "已停止" -> "■ ${item.name}（已停止）"
                        else -> "○ ${item.name}"
                    }
                    textSize = 10f
                    setTextColor(colorOf(if (item.status == "进行中") R.color.neo_pastel_purple_text else R.color.neo_text_hint))
                    isSingleLine = true
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(dp(8), dp(3), dp(8), dp(3))
                    // 正在跑的那一项用淡紫块标出来，对应参考图里的粉彩内容卡
                    if (item.status == "进行中") {
                        val d = android.graphics.drawable.GradientDrawable()
                        d.cornerRadius = dp(8).toFloat()
                        d.setColor(colorOf(R.color.neo_pastel_purple))
                        background = d
                    }
                })
            }
            if (total > limit) {
                box.addView(TextView(this).apply {
                    text = "…还有 ${total - limit} 项"
                    textSize = 10f
                    setTextColor(colorOf(R.color.neo_text_hint))
                    setPadding(dp(4), dp(1), 0, 0)
                })
            }
        }
    }

    /** 四个分区都能折叠：标题统一带底色，收起后只看得到标题，不会再混淆成另一块 */
    private fun bindHomeToggles() {
        bindOneToggle(R.id.neo_home_progress_container, R.id.neo_home_progress_toggle)
        bindOneToggle(R.id.neo_home_feature_container, R.id.neo_home_feature_toggle)
        bindOneToggle(R.id.neo_home_more_container, R.id.neo_home_more_toggle)
        bindOneToggle(R.id.neo_home_switch_container, R.id.neo_home_switch_toggle)
    }

    private fun bindOneToggle(boxId: Int, toggleId: Int) {
        val box = findViewById<LinearLayout>(boxId) ?: return
        val toggle = findViewById<TextView>(toggleId) ?: return
        toggle.setOnClickListener {
            val show = box.visibility != View.VISIBLE
            box.visibility = if (show) View.VISIBLE else View.GONE
            toggle.text = if (show) "收起 ▲" else "展开 ▼"
        }
    }

    /** 首页状态行：圆点 + 已激活/已加载/未加载 + 版本号 + 当前账号 */
    private fun updateEnergyStatus() {
        val dot = findViewById<View>(R.id.neo_home_status_dot) ?: return
        val text = findViewById<TextView>(R.id.neo_home_status_text)
        val version = findViewById<TextView>(R.id.neo_home_status_version)
        val account = findViewById<TextView>(R.id.neo_home_status_account)
        val (color, label) = when (ViewAppInfo.getRunType()) {
            RunType.MODEL -> "#4CAF50" to "运行中"
            RunType.PACKAGE -> "#4CAF50" to "已加载"
            RunType.DISABLE -> "#E0532C" to "未加载"
        }
        dot.backgroundTintList = ColorStateList.valueOf(android.graphics.Color.parseColor(color))
        text.text = label
        val versionName = ViewAppInfo.getAppVersion()
        version.text = if (versionName.isNullOrBlank()) "" else "v$versionName"
        try {
            // 优先显示支付宝回包里的真实登录账号，切号后才看得出界面跟着换了
            val uid = if (hostUid.isNotEmpty()) hostUid
                      else com.surexu.sesame.util.idMap.UserIdMap.getCurrentUid()
            val name = if (uid.isNullOrEmpty()) "" else com.surexu.sesame.util.idMap.UserIdMap.getMaskName(uid)
            account.text = when {
                !name.isNullOrEmpty() -> "账号 $name"
                uid != null && uid.length > 4 -> "账号 ${uid.takeLast(4)}"
                else -> ""
            }
        } catch (th: Throwable) {
            account.text = ""
        }
    }

    private val hitokotoList = listOf(
        "种一棵树最好的时间是十年前，其次是现在。",
        "能量不是攒出来的，是坚持出来的。",
        "每天进步一点点，复利终会开花。",
        "蚂蚁虽小，日拱一卒。",
        "早起的鸟儿有虫吃，早起的你有点数。",
        "好事多磨，好能量多攒。",
        "行百里者半九十，坚持就是胜利。",
        "绿意盎然，全靠日常。",
        "流水不争先，争的是滔滔不绝。",
        "积土成山，风雨兴焉；积水成渊，蛟龙生焉。",
    )

    private val hitokotoClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    private fun bindHitokoto() {
        val text = findViewById<TextView>(R.id.neo_home_hitokoto_text)
        showRandomHitokoto(text)
        findViewById<View>(R.id.neo_home_hitokoto_card).setOnClickListener {
            fetchHitokoto(text)
        }
    }

    /** 一言：先展示本地兜底句，同时异步请求网络一言，成功则替换。 */
    private fun fetchHitokoto(text: TextView) {
        val request = Request.Builder().url("https://v1.hitokoto.cn/?encode=json").build()
        hitokotoClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                // 网络不可用：保留当前句子
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        if (!it.isSuccessful) return
                        val body = it.body?.string().orEmpty()
                        if (body.isBlank()) return
                        val node = ObjectMapper().readTree(body)
                        val sentence = node.path("hitokoto").asText("")
                        val from = node.path("from").asText("")
                        val fromWho = node.path("from_who").asText("")
                        val suffix = when {
                            from.isNotBlank() && fromWho.isNotBlank() -> "—— $fromWho「$from」"
                            from.isNotBlank() -> "——「$from」"
                            fromWho.isNotBlank() -> "—— $fromWho"
                            else -> ""
                        }
                        if (sentence.isNotBlank()) {
                            runOnUiThread {
                                // 页面已经关掉时别再碰控件，避免网络异步回包导致崩溃 / 泄漏
                                if (isFinishing || isDestroyed) return@runOnUiThread
                                text.text = if (suffix.isNotBlank()) "$sentence $suffix" else sentence
                            }
                        }
                    }
                } catch (e: Exception) {
                    // 解析失败：保留当前句子
                }
            }
        })
    }

    private fun showRandomHitokoto(text: TextView) {
        text.text = hitokotoList.random()
    }

    private fun bindLogsActions() {
        // 日志分类标签：森林/庄园/新村/运动/其他/记录/错误/调试/运行，按「系统界面-日志展示」勾选过滤
        val tabBar = findViewById<LinearLayout>(R.id.neo_logs_tab_bar)
        val enabledTabs = uiPrefs.getStringSet(NeoSystemActivity.KEY_UI_LOG_TABS, null) ?: logTabs.toSet()
        val visibleTabs = logTabs.filter { enabledTabs.contains(it) }
        if (visibleTabs.isEmpty()) {
            tabBar.visibility = View.GONE
            findViewById<View>(R.id.neo_logs_list).visibility = View.GONE
            findViewById<View>(R.id.neo_logs_empty).visibility = View.VISIBLE
            return
        }
        if (currentLogTag !in visibleTabs) {
            currentLogTag = visibleTabs.first()
        }
        // 「其他」日志专用：按子功能下拉筛选；其它分类把这个下拉框藏起来
        val spinner = findViewById<Spinner>(R.id.neo_logs_module_spinner)
        val subFeatures = mutableListOf("全部子功能")
        subFeatures.addAll(listOf("游戏中心", "消费金", "百次立减", "花呗金",
                "内容种草滑动红包", "会员", "金豆", "种花大作战", "新村", "运动", "古树", "保护地"))
        try {
            FeatureStats.allFeatureNames().forEach {
                if (!subFeatures.contains(it)) subFeatures.add(it)
            }
        } catch (th: Throwable) {
        }
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, subFeatures).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinner.setSelection(subFeatures.indexOf(selectedLogSubFeature).coerceAtLeast(0))
        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long
            ) {
                selectedLogSubFeature = subFeatures.getOrElse(position) { "全部子功能" }
                renderLogEntries()
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        fun syncSpinnerVisibility() {
            spinner.visibility = if (currentLogTag == "其他") View.VISIBLE else View.GONE
        }
        syncSpinnerVisibility()

        val tabs = mutableListOf<TextView>()
        visibleTabs.forEachIndexed { index, name ->
            val tab = layoutInflater.inflate(R.layout.neo_item_log_tab, tabBar, false) as TextView
            tab.text = name
            tab.isSelected = index == 0
            updateLogTabStyle(tab)
            tab.setOnClickListener {
                haptic(tab)
                tabs.forEach { t ->
                    t.isSelected = false
                    updateLogTabStyle(t)
                }
                tab.isSelected = true
                updateLogTabStyle(tab)
                currentLogTag = name
                syncSpinnerVisibility()
                hideLogSearchBar()
                asyncLoadLogEntries(name, true)
            }
            tabs.add(tab)
            tabBar.addView(tab)
        }

        // 首次进入不再预读日志：改为切到日志页时 ensureLogsLoaded() 懒加载，
        // 避免启动即解析 1MB 日志拖慢首屏

        // 搜索：显示搜索栏并聚焦输入
        findViewById<View>(R.id.neo_logs_search_btn).setOnClickListener {
            haptic(it)
            showLogSearchBar()
        }
        findViewById<View>(R.id.neo_logs_search_close).setOnClickListener { hideLogSearchBar() }

        // 通俗 / 技术视图切换（右上角眼睛图标）
        logHumanMode = logUiPrefs().getBoolean("log_human_mode", true)
        val humanBtn = findViewById<View>(R.id.neo_logs_human_btn)
        fun refreshHumanBtn() {
            humanBtn.alpha = if (logHumanMode) 1f else 0.35f
        }
        refreshHumanBtn()
        humanBtn.setOnClickListener {
            haptic(it)
            logHumanMode = !logHumanMode
            logTechExpanded = false
            logUiPrefs().edit().putBoolean("log_human_mode", logHumanMode).apply()
            refreshHumanBtn()
            renderLogEntries()
            Toast.makeText(
                this,
                if (logHumanMode) "通俗视图：技术日志已折叠" else "技术视图：显示原始日志",
                Toast.LENGTH_SHORT
            ).show()
        }
        findViewById<View>(R.id.neo_logs_tech_bar).setOnClickListener {
            logTechExpanded = !logTechExpanded
            renderLogEntries()
        }
        findViewById<EditText>(R.id.neo_logs_search_input).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (findViewById<View>(R.id.neo_logs_search_bar).visibility == View.VISIBLE) {
                    renderLogEntries()
                }
            }
        })

        // 刷新：重新读取当前分类日志文件
        findViewById<View>(R.id.neo_logs_refresh_btn).setOnClickListener {
            haptic(it)
            logCache.remove(currentLogTag)
            asyncLoadLogEntries(currentLogTag, true)
            Toast.makeText(this, "已刷新", Toast.LENGTH_SHORT).show()
        }

        // 更多：复制全部 / 导出 / 清空 / 滚动到底 / 滚动顶部
        findViewById<View>(R.id.neo_logs_more_btn).setOnClickListener {
            haptic(it)
            showLogMoreDialog()
        }
    }

    /** 日志页懒加载：仅首次切入日志页时读盘解析并渲染，后续直接复用。 */
    private fun ensureLogsLoaded() {
        if (logsLoaded) return
        logsLoaded = true
        asyncLoadLogEntries(currentLogTag, true)
    }

    /** 日志分类标签选中态：选中为主色软底药丸，未选中为透明底灰色文字。 */
    private fun updateLogTabStyle(tab: TextView) {
        if (tab.isSelected) {
            tab.background = ContextCompat.getDrawable(this, R.drawable.neu_tab_selected)
            tab.setTextColor(ContextCompat.getColor(this, R.color.neo_primary))
        } else {
            tab.background = null
            tab.setTextColor(ContextCompat.getColor(this, R.color.neo_text_hint))
        }
    }

    private val logTabs = listOf("森林", "庄园", "新村", "运动", "其他", "记录", "错误", "调试", "运行")

    /** 日志页是否已完成首次加载：切到日志页时才读盘解析，避免启动即读 1MB 日志 */
    private var logsLoaded = false

    // ==================== 日志页数据对接（读取/解析/渲染，逻辑与旧版模块 UI 日志页对齐） ====================

    private data class NeoLogEntry(val lineNumber: Int, val time: String?, val tag: String?, val body: String)

    private var currentLogTag: String = "森林"

    /** 「其他」日志里当前选中的子功能；"全部子功能" 表示不过滤 */
    private var selectedLogSubFeature: String = "全部子功能"

    /** 通俗视图开关（默认开）：Hook/接口等技术日志折叠，只留人话 */
    private var logHumanMode: Boolean = true

    /** 技术信息折叠条是否展开 */
    private var logTechExpanded: Boolean = false

    private fun logUiPrefs() = getSharedPreferences("sesame_ui_state", Context.MODE_PRIVATE)
    private var logEntries: List<NeoLogEntry> = emptyList()

    private val logRefreshHandler = Handler(Looper.getMainLooper())
    private var logRefreshRunning = false
    private var lastLogStamp: LogFileStamp? = null
    private val logRefreshRunnable = object : Runnable {
        override fun run() {
            refreshLogsIfChanged()
            if (logRefreshRunning) {
                logRefreshHandler.postDelayed(this, LOG_REFRESH_INTERVAL_MS)
            }
        }
    }

    // ===== 激活探测（与旧版模块 UI 主页面逻辑对齐）=====
    /** 激活探测轮询器：DISABLE 时周期发查询广播，直到收到模块回包或达上限 */
    private val runTypeProbeHandler = Handler(Looper.getMainLooper())
    private var runTypeProbeTimes = 0
    private val runTypeProbeRunnable = object : Runnable {
        override fun run() {
            if (ViewAppInfo.getRunType() == RunType.DISABLE) {
                runTypeProbeTimes++
                updateEnergyStatus()
                if (runTypeProbeTimes < MAX_RUN_TYPE_PROBE_TIMES) {
                    sendRunTypeQueryBroadcast()
                    runTypeProbeHandler.postDelayed(this, RUN_TYPE_PROBE_INTERVAL_MS)
                }
            } else {
                runTypeProbeTimes = 0
            }
        }
    }

    private data class LogFileStamp(val length: Long, val modified: Long)

    /** 分类标签 → 对应日志文件。新村 / 运动各自独立文件，'记录' 对应金豆记录。 */
    private fun logFileFor(tag: String): File = when (tag) {
        "森林" -> FileUtil.getForestLogFile()
        "庄园" -> FileUtil.getFarmLogFile()
        "新村" -> FileUtil.getVillageLogFile()
        "运动" -> FileUtil.getSportsLogFile()
        "其他" -> FileUtil.getOtherLogFile()
        "记录" -> FileUtil.getGoldenBeansLogFile()
        "错误" -> FileUtil.getErrorLogFile()
        "调试" -> FileUtil.getDebugLogFile()
        else -> FileUtil.getRuntimeLogFile()
    }

    private fun logFileStamp(file: File?): LogFileStamp? =
        if (file != null && file.exists()) LogFileStamp(file.length(), file.lastModified()) else null

    /** 读取日志文件并按行解析为条目；无时间戳的行合并到上一条。仅读尾部，限制条目数。 */
    private fun loadLogEntries(file: File?): List<NeoLogEntry> {
        if (file == null || !file.exists()) {
            return emptyList()
        }
        // 两种格式：
        // runtime/system/debug: 13:34:10.251 RUNTIME: 执行结束-庄园
        // forest/farm/goldenbeans/other: 19:37:33.150 森林签到...
        val timeRegex = Regex("^(\\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s+(?:(\\w+):\\s*)?(.*)$")
        val entries = ArrayDeque<NeoLogEntry>()
        return try {
            val text = readTailText(file, LOG_MAX_TAIL_BYTES)
            var lineNumber = 0
            for (line in text.lineSequence()) {
                lineNumber++
                val match = timeRegex.find(line)
                if (match != null) {
                    entries.addLast(
                        NeoLogEntry(
                            lineNumber = lineNumber,
                            time = match.groupValues[1],
                            tag = match.groupValues[2].takeIf { it.isNotEmpty() },
                            body = match.groupValues[3]
                        )
                    )
                } else {
                    if (entries.isNotEmpty()) {
                        val last = entries.removeLast()
                        entries.addLast(last.copy(body = last.body + "\n" + line))
                    } else {
                        entries.addLast(NeoLogEntry(lineNumber, null, null, line))
                    }
                }
                while (entries.size > LOG_MAX_ENTRIES) {
                    entries.removeFirst()
                }
            }
            entries.toList()
        } catch (e: Throwable) {
            emptyList()
        }
    }

    /** 从文件尾部读取文本，最多 maxBytes 字节；非从头读取时丢弃首个可能被截断的行。 */
    private fun readTailText(file: File, maxBytes: Long): String {
        val length = file.length()
        if (length <= 0L) {
            return ""
        }
        val start = maxOf(0L, length - maxBytes)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(start)
            val bytes = ByteArray((length - start).toInt())
            raf.readFully(bytes)
            var text = String(bytes, Charsets.UTF_8)
            if (start > 0L) {
                val idx = text.indexOf('\n')
                text = if (idx >= 0) text.substring(idx + 1) else ""
            }
            return text
        }
    }

    /** 按当前分类 + 搜索词渲染日志列表；空结果显示空态卡。
     *  @param scrollToBottom 渲染完成后是否滚动到最新日志（切分类/首次进入/手动刷新为 true，自动刷新保持当前位置）
     *  @implNote 采用 View 复用：数据变化时只更新已有条目文本、按需新增/移除，
     *            不再每次 removeAllViews + 全量 inflate，避免日志量大时主线程被反复重建数百个 View 卡死。 */
    private fun renderLogEntries(scrollToBottom: Boolean = false) {
        val list = findViewById<LinearLayout>(R.id.neo_logs_list)
        val empty = findViewById<View>(R.id.neo_logs_empty)
        val query = findViewById<EditText>(R.id.neo_logs_search_input).text.toString().trim()
        // 通俗视图：Hook 注入、接口返回等技术日志剥离出来，单独折叠成"技术信息"
        val techEntries = if (logHumanMode) {
            logEntries.filter { NeoLogText.isTechnical(it.tag, it.body) }
        } else {
            emptyList()
        }
        val baseEntries = if (logHumanMode) {
            logEntries.filterNot { NeoLogText.isTechnical(it.tag, it.body) }
        } else {
            logEntries
        }
        val shownEntries = if (logHumanMode && logTechExpanded) baseEntries + techEntries else baseEntries
        val techBar = findViewById<View>(R.id.neo_logs_tech_bar)
        if (logHumanMode && techEntries.isNotEmpty()) {
            techBar.visibility = View.VISIBLE
            findViewById<TextView>(R.id.neo_logs_tech_text).text = if (logTechExpanded) {
                "技术信息 ${techEntries.size} 条（点击收起）"
            } else {
                "技术信息 ${techEntries.size} 条（Hook 注入、接口返回等）"
            }
        } else {
            techBar.visibility = View.GONE
        }
        // 过滤在数据量最大 500 条内进行，纯内存操作很快；真正的卡顿源是 inflate/布局，已用复用解决
        // 「其他」日志按选中的子功能名过滤：各模块日志正文里都带自己的功能名
        val subFilter = if (currentLogTag == "其他") selectedLogSubFeature else "全部子功能"
        val filtered = shownEntries.filter { e ->
            val matchSub = subFilter == "全部子功能" || e.body.contains(subFilter, ignoreCase = true)
            matchSub && (query.isEmpty() ||
                (e.tag?.contains(query, ignoreCase = true) == true) ||
                e.body.contains(query, ignoreCase = true))
        }
        if (filtered.isEmpty()) {
            list.removeAllViews()
            empty.visibility = View.VISIBLE
            list.visibility = View.GONE
            return
        }
        empty.visibility = View.GONE
        list.visibility = View.VISIBLE

        // 数据多时只渲染最新 LOG_MAX_RENDER 条
        val toRender = if (filtered.size > LOG_MAX_RENDER) filtered.takeLast(LOG_MAX_RENDER) else filtered
        val childCount = list.childCount
        val count = toRender.size

        // 1) 更新/复用已有条目：只改文本，不重建 View
        repeat(minOf(childCount, count)) { i ->
            val entry = toRender[i]
            val card = list.getChildAt(i)
            card.findViewById<TextView>(R.id.entry_tag).text =
                entry.tag?.let { NeoLogText.tag(it) } ?: currentLogTag
            card.findViewById<TextView>(R.id.entry_time).text = entry.time ?: ""
            val body = card.findViewById<TextView>(R.id.entry_body)
            if (entry.body.isBlank()) {
                body.visibility = View.GONE
                body.text = ""
            } else {
                body.visibility = View.VISIBLE
                body.text = if (logHumanMode) NeoLogText.body(entry.body) else entry.body
            }
            card.setOnClickListener { copyLogEntry(entry) }
            card.findViewById<View>(R.id.entry_copy).setOnClickListener { copyLogEntry(entry) }
        }

        // 2) 现有 View 多于需要：移除多余的（从尾往头移除，避免索引错乱）
        if (childCount > count) {
            for (i in childCount - 1 downTo count) {
                list.removeViewAt(i)
            }
        }

        // 3) 需要更多条目：按需增量 inflate 新增
        if (count > childCount) {
            for (i in childCount until count) {
                val entry = toRender[i]
                val card = layoutInflater.inflate(R.layout.neo_item_log_entry, list, false)
                card.findViewById<TextView>(R.id.entry_tag).text =
                    entry.tag?.let { NeoLogText.tag(it) } ?: currentLogTag
                card.findViewById<TextView>(R.id.entry_time).text = entry.time ?: ""
                val body = card.findViewById<TextView>(R.id.entry_body)
                if (entry.body.isBlank()) {
                    body.visibility = View.GONE
                } else {
                    body.text = if (logHumanMode) NeoLogText.body(entry.body) else entry.body
                }
                card.setOnClickListener { copyLogEntry(entry) }
                card.findViewById<View>(R.id.entry_copy).setOnClickListener { copyLogEntry(entry) }
                list.addView(card)
            }
        }

        if (scrollToBottom) {
            val scroll = findViewById<ScrollView>(R.id.neo_logs_scroll)
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    /** 复制单条日志（时间 + TAG: 正文）。 */
    private fun copyLogEntry(entry: NeoLogEntry) {
        val text = buildString {
            if (!entry.time.isNullOrBlank()) append(entry.time).append(' ')
            if (!entry.tag.isNullOrBlank()) append(entry.tag).append(": ")
            append(entry.body)
        }
        try {
            val manager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
            manager.setPrimaryClip(ClipData.newPlainText(entry.tag ?: "日志", text))
            Toast.makeText(this, "已复制本条日志", Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            // 剪贴板写入失败不影响页面，静默忽略
        }
    }

    /** 日志自动刷新：每秒比较文件签名，有新内容才重新加载（仅日志页可见时运行）。 */
    private fun startLogAutoRefresh() {
        if (logRefreshRunning) return
        logRefreshRunning = true
        logRefreshHandler.post(logRefreshRunnable)
    }

    private fun stopLogAutoRefresh() {
        logRefreshRunning = false
        logRefreshHandler.removeCallbacks(logRefreshRunnable)
    }

    private fun refreshLogsIfChanged() {
        val file = logFileFor(currentLogTag)
        val stamp = logFileStamp(file)
        if (stamp == null || stamp == lastLogStamp) {
            return
        }
        asyncLoadLogEntries(currentLogTag)
    }

    /** 单条日志缓存：文件签名 + 解析结果，文件未变化时切换分类直接复用，避免重复读盘解析。 */
    private data class CachedLog(val stamp: LogFileStamp?, val entries: List<NeoLogEntry>)

    private val logParseExecutor = Executors.newSingleThreadExecutor()
    private val logTaskSeq = AtomicInteger(0)
    private val logCache = HashMap<String, CachedLog>()

    /** 异步加载指定分类日志：缓存未变化直接复用（秒开）；变化则后台读盘解析，结果回主线程渲染。
     *  @param scrollToBottom 渲染后是否滚动到最新日志 */
    private fun asyncLoadLogEntries(tag: String, scrollToBottom: Boolean = false) {
        val file = logFileFor(tag)
        val stamp = logFileStamp(file)
        val cached = logCache[tag]
        if (cached != null && cached.stamp == stamp) {
            logEntries = cached.entries
            lastLogStamp = stamp
            renderLogEntries(scrollToBottom)
            return
        }
        // 乐观更新签名，防止自动刷新每秒重复发起同一文件的解析
        lastLogStamp = stamp
        val seq = logTaskSeq.incrementAndGet()
        logParseExecutor.execute {
            val entries = loadLogEntries(file)
            val newStamp = logFileStamp(file)
            logCache[tag] = CachedLog(newStamp, entries)
            runOnUiThread {
                // 页面已销毁或用户已切换其他分类/发起了更新任务，丢弃过期结果避免覆盖
                if (isDestroyed || isFinishing || logTaskSeq.get() != seq) return@runOnUiThread
                logEntries = entries
                lastLogStamp = newStamp
                renderLogEntries(scrollToBottom)
            }
        }
    }

    private fun showLogSearchBar() {
        findViewById<View>(R.id.neo_logs_search_bar).visibility = View.VISIBLE
        val input = findViewById<EditText>(R.id.neo_logs_search_input)
        input.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
        imm?.showSoftInput(input, 0)
    }

    private fun hideLogSearchBar() {
        findViewById<View>(R.id.neo_logs_search_bar).visibility = View.GONE
        val input = findViewById<EditText>(R.id.neo_logs_search_input)
        input.setText("")
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
        imm?.hideSoftInputFromWindow(input.windowToken, 0)
    }

    /** 更多菜单：复制全部（当前筛选结果） / 导出日志 / 清空日志 / 滚动到底 / 滚动顶部。 */
    private fun showLogMoreDialog() {
        val file = logFileFor(currentLogTag)
        showOptionDialog(
            "日志操作",
            listOf("复制全部", "导出日志", "清空日志", "滚动到底", "滚动顶部")
        ) { which ->
            when (which) {
                0 -> {
                    val query = findViewById<EditText>(R.id.neo_logs_search_input).text.toString().trim()
                    val filtered = logEntries.filter { e ->
                        query.isEmpty() ||
                            (e.tag?.contains(query, ignoreCase = true) == true) ||
                            e.body.contains(query, ignoreCase = true)
                    }
                    val text = filtered.reversed().joinToString("\n") {
                        buildString {
                            if (!it.time.isNullOrBlank()) append(it.time).append(' ')
                            if (!it.tag.isNullOrBlank()) append(it.tag).append(": ")
                            append(it.body)
                        }
                    }
                    if (text.isBlank()) {
                        Toast.makeText(this, "暂无可复制的日志", Toast.LENGTH_SHORT).show()
                    } else {
                        try {
                            val manager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            manager?.setPrimaryClip(ClipData.newPlainText(currentLogTag, text))
                            Toast.makeText(this, "已复制 ${filtered.size} 条日志", Toast.LENGTH_SHORT).show()
                        } catch (t: Throwable) {
                            Toast.makeText(this, "复制失败", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                1 -> {
                    val exported = FileUtil.exportFile(file)
                    if (exported != null) {
                        Toast.makeText(this, "已导出: " + exported.path, Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "导出失败", Toast.LENGTH_SHORT).show()
                    }
                }
                2 -> {
                    if (FileUtil.clearFile(file)) {
                        lastLogStamp = null
                        logEntries = loadLogEntries(file)
                        renderLogEntries()
                        Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "清空失败", Toast.LENGTH_SHORT).show()
                    }
                }
                3 -> {
                    findViewById<ScrollView>(R.id.neo_logs_scroll).fullScroll(ScrollView.FOCUS_DOWN)
                }
                4 -> {
                    findViewById<ScrollView>(R.id.neo_logs_scroll).fullScroll(ScrollView.FOCUS_UP)
                }
            }
        }
    }

    private fun bindSettingsActions() {
        val list = findViewById<LinearLayout>(R.id.neo_setting_list)
        val marginPx = (12 * resources.displayMetrics.density).toInt()
        settings.forEach { setting ->
            val card = layoutInflater.inflate(R.layout.neo_item_setting, list, false)
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins(0, 0, 0, marginPx)
            card.layoutParams = lp

            card.findViewById<ImageView>(R.id.neo_setting_icon).setImageResource(setting.iconRes)
            card.findViewById<TextView>(R.id.neo_setting_name).text = setting.name
            val sub = card.findViewById<TextView>(R.id.neo_setting_sub)
            if (setting.sub != null) {
                sub.text = setting.sub
                sub.visibility = View.VISIBLE
            }
            card.setOnClickListener {
                haptic(card)
                when (setting.name) {
                    "账号" -> startActivity(Intent(this, NeoAccountActivity::class.java))
                    "备份与恢复" -> showBackupRestoreDialog()
                    "关于" -> startActivity(Intent(this, NeoAboutActivity::class.java))
                    "系统界面" -> startActivity(Intent(this, NeoSystemActivity::class.java))
                    "服务" -> startActivity(Intent(this, NeoServiceActivity::class.java))
                    "服务器地址" -> Toast.makeText(
                        this,
                        "你好，陌生人，很高兴你来了，也许你现在无法打开这里，但求知，好奇和思考从来不该被一道网络的墙定义。",
                        Toast.LENGTH_LONG
                    ).show()
                    else -> Toast.makeText(this, "${setting.name}：功能对接中", Toast.LENGTH_SHORT).show()
                }
            }
            list.addView(card)
        }
        updateAccountSettingCard()
        updateSystemSettingSub()
    }

    // ==================== 账号卡片展示（设置页顶部「账号」条目） ====================

    /** 读取指定账号 self.json 得到用户实体（与账号配置页同款加载方式）。 */
    private fun loadUser(userId: String?): UserEntity? {
        if (StringUtil.isEmpty(userId)) return null
        UserIdMap.loadSelf(userId!!)
        return UserIdMap.get(userId)
    }

    /** 把设置页「账号」卡片同步为当前选中账号的头像与账号信息。 */
    private fun refreshAccountSettingCard(card: View) {
        val avatar = card.findViewById<NeoAsyncAvatarView>(R.id.neo_setting_avatar)
        val icon = card.findViewById<ImageView>(R.id.neo_setting_icon)
        val name = card.findViewById<TextView>(R.id.neo_setting_name)
        val sub = card.findViewById<TextView>(R.id.neo_setting_sub)

        val userId = restoreSelectedAccount()
        if (StringUtil.isEmpty(userId)) {
            // 未选择账号：保持默认图标与标题，副标题提示默认配置
            avatar.load(null)
            avatar.visibility = View.GONE
            icon.visibility = View.VISIBLE
            name.text = "账号"
            sub.text = "默认配置"
            sub.visibility = View.VISIBLE
            return
        }
        val entity = loadUser(userId)
        val showName = entity?.showName
        name.text = if (!StringUtil.isEmpty(showName)) showName else "账号 $userId"
        sub.text = if (StringUtil.isEmpty(entity?.account)) "userId: $userId" else "账号: ${entity?.account}"
        sub.visibility = View.VISIBLE
        if (!StringUtil.isEmpty(entity?.avatar)) {
            avatar.load(entity?.avatar)
            avatar.visibility = View.VISIBLE
            icon.visibility = View.GONE
        } else {
            avatar.load(null)
            avatar.visibility = View.GONE
            icon.visibility = View.VISIBLE
        }
    }

    /** 设置页已构建时：按名称定位「账号」条目并刷新账号信息（切换账号返回主界面时用到）。 */
    private fun updateAccountSettingCard() {
        val list = findViewById<LinearLayout>(R.id.neo_setting_list) ?: return
        if (list.childCount == 0 || !pageInited[3]) return
        val idx = settings.indexOfFirst { it.name == "账号" }
        if (idx in 0 until list.childCount) {
            refreshAccountSettingCard(list.getChildAt(idx))
        }
    }

    // ==================== 系统界面设置应用（悬浮底栏 / 触感 / 设置页摘要） ====================

    /** 应用「悬浮底栏」：开启时底部导航容器变为圆角凸起胶囊（左右 12dp + 底部 8dp），关闭恢复原样。 */
    private fun applyFloatNav() {
        val root = findViewById<View>(R.id.neo_nav_bar_root) ?: return
        val lp = root.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        if (uiPrefs.getBoolean(NeoSystemActivity.KEY_UI_FLOAT_NAV, false)) {
            val m = dp(12)
            val mb = dp(8)
            lp.leftMargin = m
            lp.rightMargin = m
            lp.bottomMargin = mb
            root.background = ContextCompat.getDrawable(this, R.drawable.neu_nav_float)
        } else {
            lp.leftMargin = 0
            lp.rightMargin = 0
            lp.bottomMargin = 0
            root.background = null
        }
        root.layoutParams = lp
    }

    /** 触感反馈：开关开启时振动（优先 Vibrator 直振，失败回退系统反馈）。 */
    private fun haptic(view: View) {
        if (!uiPrefs.getBoolean(NeoSystemActivity.KEY_UI_HAPTIC, false)) return
        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (vibrator != null && vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(25, 160))
                } else {
                    vibrator.vibrate(25)
                }
                return
            }
        } catch (_: Exception) {
            // 无 VIBRATE 权限等异常时回退系统反馈
        }
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    /** 主题是否显式浅色（跟随系统=false 且 深色=false）。 */
    private fun themeIsLight(): Boolean =
        !(AppConfig.INSTANCE.followSystem ?: true) && !(AppConfig.INSTANCE.darkMode ?: false)

    /** 刷新设置页「系统界面」条目副标题：主题 · 缩放 · 悬浮状态。 */
    private fun updateSystemSettingSub() {
        val list = findViewById<LinearLayout>(R.id.neo_setting_list) ?: return
        if (list.childCount == 0) return
        // 账号条目排到列表第一位后，不能再固定取第一项，需按名称定位「系统界面」条目
        val idx = settings.indexOfFirst { it.name == "系统界面" }
        if (idx < 0 || idx >= list.childCount) return
        val sub = list.getChildAt(idx).findViewById<TextView>(R.id.neo_setting_sub) ?: return
        val theme = when {
            themeIsLight() -> "浅色"
            AppConfig.INSTANCE.darkMode ?: false -> "深色"
            else -> "跟随系统"
        }
        val scale = uiPrefs.getInt(NeoSystemActivity.KEY_UI_SCALE, 100)
        val floatOn = uiPrefs.getBoolean(NeoSystemActivity.KEY_UI_FLOAT_NAV, false)
        sub.text = "$theme · 缩放${scale}%" + if (floatOn) " · 悬浮开" else ""
        sub.visibility = View.VISIBLE
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private val uiPrefs by lazy { getSharedPreferences(PREFS_UI, MODE_PRIVATE) }

    /** 读取上次选中的账号 id(可能已失效,由账号页渲染时对目录校验并回退默认) */
    private fun restoreSelectedAccount(): String? {
        val last = uiPrefs.getString(KEY_LAST_SELECTED_USER, null) ?: return null
        return if (StringUtil.isEmpty(last)) null else last
    }

    private data class NeoSetting(val name: String, val iconRes: Int, val sub: String? = null)

    private val settings by lazy {
        listOf(
            NeoSetting("账号", R.drawable.ic_neo_profile),
            NeoSetting("系统界面", R.drawable.ic_neo_sysui),
            NeoSetting("服务", R.drawable.ic_neo_service),
            NeoSetting("备份与恢复", R.drawable.ic_neo_backup),
            NeoSetting("关于", R.drawable.ic_neo_about),
            NeoSetting("服务器地址", R.drawable.ic_neo_server),
        )
    }

    /** 备份与恢复主弹窗：立即备份 / 从备份恢复 / 从文件导入 / 清除备份。 */
    private fun showBackupRestoreDialog() {
        showOptionDialog(
            "备份与恢复",
            listOf("立即备份", "从备份恢复", "从文件导入", "清除备份")
        ) { index ->
            when (index) {
                0 -> backupNow()
                1 -> showRestoreListDialog()
                2 -> importBackupFromFile()
                3 -> clearBackups()
            }
        }
    }

    /** 立即备份：弹框让用户自定义文件名与保存位置（默认位置备份目录或系统文件选择器）。 */
    private fun backupNow() {
        try {
            val src = FileUtil.getDefaultConfigV2File()
            if (!src.exists()) {
                Toast.makeText(this, "暂无配置文件", Toast.LENGTH_SHORT).show()
                return
            }
            val json = FileUtil.readFromFile(src)
            val defaultName = FileUtil.BACKUP_FILE_PREFIX + "default_" +
                SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date()) + FileUtil.BACKUP_FILE_EXT

            val dialog = Dialog(this)
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            dialog.setContentView(R.layout.neo_dialog_field_edit)
            dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            dialog.setCanceledOnTouchOutside(true)
            dialog.window?.setLayout(
                resources.displayMetrics.widthPixels - (64 * resources.displayMetrics.density).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )

            dialog.findViewById<TextView>(R.id.neo_edit_title).text = "立即备份（可改文件名）"
            dialog.findViewById<TextView>(R.id.neo_edit_ok).visibility = View.GONE
            dialog.findViewById<TextView>(R.id.neo_edit_cancel).setOnClickListener { dialog.dismiss() }

            val content = dialog.findViewById<FrameLayout>(R.id.neo_edit_content)
            val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

            val nameInput = EditText(this).apply {
                setText(defaultName)
                setTextColor(ContextCompat.getColor(this@NeoMainActivity, R.color.neo_text_primary))
                setTextSize(14f)
                background = ContextCompat.getDrawable(this@NeoMainActivity, R.drawable.neu_input_bg)
                setPadding(dp(14), dp(10), dp(14), dp(10))
            }
            inner.addView(
                nameInput,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )

            fun normalizeName(): String {
                var name = nameInput.text.toString().trim()
                if (name.isEmpty()) return ""
                if (!name.endsWith(FileUtil.BACKUP_FILE_EXT)) name += FileUtil.BACKUP_FILE_EXT
                return name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            }

            inner.addView(
                buildBackupActionRow("保存到默认位置", "写入备份目录 ${FileUtil.getBackupDirectoryFile().path}") {
                    val name = normalizeName()
                    if (name.isEmpty()) {
                        Toast.makeText(this, "文件名不能为空", Toast.LENGTH_SHORT).show()
                        return@buildBackupActionRow
                    }
                    val target = File(FileUtil.getBackupDirectoryFile(), name)
                    if (FileUtil.write2File(json, target)) {
                        dialog.dismiss()
                        Toast.makeText(this, "已备份：${target.name}", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "备份失败", Toast.LENGTH_SHORT).show()
                    }
                }
            )
            inner.addView(
                buildBackupActionRow("选择位置保存", "用系统文件选择器自定义目录与文件名") {
                    val name = normalizeName()
                    if (name.isEmpty()) {
                        Toast.makeText(this, "文件名不能为空", Toast.LENGTH_SHORT).show()
                        return@buildBackupActionRow
                    }
                    try {
                        pendingBackupJson = json
                        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "application/json"
                            putExtra(Intent.EXTRA_TITLE, name)
                        }
                        startActivityForResult(intent, REQUEST_EXPORT_BACKUP)
                    } catch (e: Exception) {
                        Toast.makeText(this, "无法打开文件选择器：" + (e.message ?: ""), Toast.LENGTH_SHORT).show()
                    }
                }
            )

            content.addView(
                inner,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
            dialog.show()
        } catch (e: Exception) {
            Toast.makeText(this, "备份失败：" + (e.message ?: ""), Toast.LENGTH_SHORT).show()
        }
    }

    /** 立即备份弹窗内的操作行（拟态样式），点击执行回调。 */
    private fun buildBackupActionRow(name: String, sub: String, onClick: () -> Unit): View {
        val row = TextView(this).apply {
            text = "$name\n$sub"
            textSize = 14f
            setTextColor(ContextCompat.getColor(this@NeoMainActivity, R.color.neo_primary))
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = ContextCompat.getDrawable(this@NeoMainActivity, R.drawable.neu_input_bg)
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, 0, 0, dp(8))
        row.layoutParams = lp
        row.setOnClickListener {
            haptic(row)
            onClick()
        }
        return row
    }

    /** 从备份恢复：列出备份目录全部配置备份，点选即写回默认配置并重载。 */
    private fun showRestoreListDialog() {
        val files = listBackupFiles()
        if (files.isEmpty()) {
            Toast.makeText(this, "暂无备份文件", Toast.LENGTH_SHORT).show()
            return
        }
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        showOptionDialog(
            "从备份恢复（点选立即恢复）",
            files.map { "${it.name}（${fmt.format(Date(it.lastModified()))}）" }
        ) { index ->
            restoreFromBackupFile(files[index])
        }
    }

    private fun restoreFromBackupFile(backup: File) {
        try {
            val json = FileUtil.readFromFile(backup)
            if (json.isNullOrEmpty()) {
                Toast.makeText(this, "备份文件为空", Toast.LENGTH_SHORT).show()
                return
            }
            val userId = restoreSelectedAccount()
            if (StringUtil.isEmpty(userId)) {
                FileUtil.setDefaultConfigV2File(json)
            } else {
                FileUtil.setConfigV2File(userId!!, json)
            }
            ConfigV2.unload()
            ConfigV2.load(userId)
            // 保存配置只重新初始化、不触发任务执行（reinit 广播）
            sendBroadcast(Intent("com.eg.android.AlipayGphone.sesame.reinit"))
            Toast.makeText(this, "已从 ${backup.name} 恢复", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "恢复失败：" + (e.message ?: ""), Toast.LENGTH_SHORT).show()
        }
    }

    /** 从文件导入：拉起系统文件选择器选外部备份 json，写回默认配置并重载。 */
    private fun importBackupFromFile() {
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain", "*/*"))
            }
            startActivityForResult(intent, REQUEST_IMPORT_BACKUP)
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开文件选择器：" + (e.message ?: ""), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_EXPORT_BACKUP) {
            if (resultCode == RESULT_OK && data != null && data.data != null) {
                try {
                    val json = pendingBackupJson
                    if (json != null) {
                        contentResolver.openOutputStream(data.data!!)?.use { out ->
                            out.write(json.toByteArray(Charsets.UTF_8))
                        }
                        Toast.makeText(this, "已备份到所选位置", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "备份失败：无待写入内容", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(this, "备份失败：" + (e.message ?: ""), Toast.LENGTH_SHORT).show()
                }
            }
            pendingBackupJson = null
            return
        }
        if (requestCode != REQUEST_IMPORT_BACKUP || resultCode != RESULT_OK || data == null) return
        val uri: Uri? = data.data
        if (uri == null) return
        try {
            val json = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            if (json.isNullOrBlank()) {
                Toast.makeText(this, "文件内容为空", Toast.LENGTH_SHORT).show()
                return
            }
            val userId = restoreSelectedAccount()
            if (StringUtil.isEmpty(userId)) {
                FileUtil.setDefaultConfigV2File(json)
            } else {
                FileUtil.setConfigV2File(userId!!, json)
            }
            ConfigV2.unload()
            ConfigV2.load(userId)
            sendBroadcast(Intent("com.eg.android.AlipayGphone.sesame.restart"))
            Toast.makeText(this, "已从外部文件恢复配置", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "导入失败：" + (e.message ?: ""), Toast.LENGTH_SHORT).show()
        }
    }

    /** 清除备份：删除备份目录下全部配置备份文件（保留 config_v2.prev.json 写盘快照）。 */
    private fun clearBackups() {
        try {
            val files = listBackupFiles()
            var count = 0
            files.forEach { if (it.delete()) count++ }
            Toast.makeText(
                this,
                if (count > 0) "已清除 $count 份备份" else "无备份可清除",
                Toast.LENGTH_SHORT
            ).show()
        } catch (e: Exception) {
            Toast.makeText(this, "清除失败：" + (e.message ?: ""), Toast.LENGTH_SHORT).show()
        }
    }

    private fun listBackupFiles(): List<File> =
        FileUtil.getBackupDirectoryFile().listFiles { f ->
            f.isFile && f.name.startsWith(FileUtil.BACKUP_FILE_PREFIX) && f.name.endsWith(FileUtil.BACKUP_FILE_EXT)
        }?.sortedByDescending { it.lastModified() } ?: emptyList()

    /** 通用拟态选项弹窗：标题 + 选项列表，点选回调索引后关闭。 */
    private fun showOptionDialog(title: String, options: List<String>, onPick: (Int) -> Unit) {
        if (options.isEmpty()) {
            Toast.makeText(this, "无可用选项", Toast.LENGTH_SHORT).show()
            return
        }
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.neo_dialog_field_edit)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setCanceledOnTouchOutside(true)
        val width = resources.displayMetrics.widthPixels - (64 * resources.displayMetrics.density).toInt()
        dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)

        dialog.findViewById<TextView>(R.id.neo_edit_title).text = title
        dialog.findViewById<TextView>(R.id.neo_edit_ok).visibility = View.GONE
        dialog.findViewById<TextView>(R.id.neo_edit_cancel).setOnClickListener { dialog.dismiss() }

        val content = dialog.findViewById<FrameLayout>(R.id.neo_edit_content)
        val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        options.forEachIndexed { index, opt ->
            val optView = TextView(this).apply {
                text = opt
                setTextColor(ContextCompat.getColor(this@NeoMainActivity, R.color.neo_text_primary))
                textSize = 14f
                setPadding(
                    (14 * resources.displayMetrics.density).toInt(),
                    (10 * resources.displayMetrics.density).toInt(),
                    (14 * resources.displayMetrics.density).toInt(),
                    (10 * resources.displayMetrics.density).toInt()
                )
                background = ContextCompat.getDrawable(this@NeoMainActivity, R.drawable.neu_input_bg)
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.setMargins(0, 0, 0, (8 * resources.displayMetrics.density).toInt())
                layoutParams = lp
                setOnClickListener {
                    onPick(index)
                    dialog.dismiss()
                }
            }
            inner.addView(optView)
        }
        content.addView(
            inner,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        dialog.show()
    }

    /** 一级目录功能清单：名称 / 图标资源。逐个对接后把对应卡片 onClick 换成真实跳转。 */
    private data class NeoFeature(val name: String, val iconRes: Int)

    private val featureCards = mutableListOf<View>()

    private val features = listOf(
        NeoFeature("基础", R.drawable.ic_neo_feat_base),
        NeoFeature("森林", R.drawable.ic_neo_feat_forest),
        NeoFeature("庄园", R.drawable.ic_neo_feat_farm),
        NeoFeature("新村", R.drawable.ic_neo_feat_village),
        NeoFeature("运动", R.drawable.ic_neo_feat_sport),
        NeoFeature("其他", R.drawable.ic_neo_feat_other),
    )

    private fun buildFeatureGrid() {
        val list = findViewById<LinearLayout>(R.id.neo_feature_grid)
        val marginPx = (12 * resources.displayMetrics.density).toInt()
        features.forEach { feature ->
            val card = layoutInflater.inflate(R.layout.neo_item_feature, list, false)
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins(0, 0, 0, marginPx)
            card.layoutParams = lp

            card.findViewById<ImageView>(R.id.neo_item_icon).setImageResource(feature.iconRes)
            card.findViewById<TextView>(R.id.neo_item_name).text = feature.name
            card.setOnClickListener {
                haptic(card)
                when (feature.name) {
                    "基础" -> startActivity(
                        Intent(this, NeoGroupFieldsActivity::class.java).putExtra(
                            NeoGroupFieldsActivity.EXTRA_GROUP_CODE,
                            ModelGroup.BASE.code
                        )
                    )
                    "森林" -> startActivity(
                        Intent(this, NeoGroupFieldsActivity::class.java).putExtra(
                            NeoGroupFieldsActivity.EXTRA_GROUP_CODE,
                            ModelGroup.FOREST.code
                        )
                    )
                    "庄园" -> startActivity(
                        Intent(this, NeoGroupFieldsActivity::class.java).putExtra(
                            NeoGroupFieldsActivity.EXTRA_GROUP_CODE,
                            ModelGroup.FARM.code
                        )
                    )
                    "新村" -> startActivity(
                        Intent(this, NeoGroupFieldsActivity::class.java).putExtra(
                            NeoGroupFieldsActivity.EXTRA_GROUP_CODE,
                            ModelGroup.STALL.code
                        )
                    )
                    "运动" -> startActivity(
                        Intent(this, NeoGroupFieldsActivity::class.java).putExtra(
                            NeoGroupFieldsActivity.EXTRA_GROUP_CODE,
                            ModelGroup.SPORTS.code
                        )
                    )
                    "其他" -> startActivity(
                        Intent(this, NeoGroupFieldsActivity::class.java).putExtra(
                            NeoGroupFieldsActivity.EXTRA_GROUP_CODE,
                            ModelGroup.OTHER.code
                        )
                    )
                    else -> Toast.makeText(this, "${feature.name}：功能对接中，敬请期待", Toast.LENGTH_SHORT).show()
                }
            }
            list.addView(card)
            featureCards.add(card)
        }
        // 功能搜索：按名称实时过滤卡片
        findViewById<EditText>(R.id.neo_feature_search_input).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s.toString().trim()
                featureCards.forEach { card ->
                    val name = card.findViewById<TextView>(R.id.neo_item_name).text.toString()
                    card.visibility =
                        if (query.isEmpty() || name.contains(query, ignoreCase = true)) View.VISIBLE else View.GONE
                }
            }
        })
    }
}
