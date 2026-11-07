package com.surexu.sesame.ui.neo

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.surexu.sesame.R
import com.surexu.sesame.data.AppConfig
import com.surexu.sesame.data.ConfigPreload
import com.surexu.sesame.data.ConfigV2
import com.surexu.sesame.data.Model
import com.surexu.sesame.data.ModelConfig
import com.surexu.sesame.data.ModelField
import com.surexu.sesame.data.ModelGroup
import com.surexu.sesame.data.modelFieldExt.ChoiceModelField
import com.surexu.sesame.data.modelFieldExt.EmptyModelField
import com.surexu.sesame.data.modelFieldExt.IntegerModelField
import com.surexu.sesame.entity.MemberBenefit
import com.surexu.sesame.ui.neo.NeoSelectionEditActivity
import com.surexu.sesame.util.LanguageUtil

/** 分组配置二级页：拟态风格渲染某 ModelGroup 全部配置项，改动写回 ConfigV2。 */
class NeoGroupFieldsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_GROUP_CODE = "group_code"
    }

    private var group: ModelGroup? = null
    private var modified = false
    private var currentQuery = ""

    override fun attachBaseContext(newBase: Context) {
        ThemeUtil.applyNightMode()
        super.attachBaseContext(LanguageUtil.setLocal(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.neo_page_group_fields)
        window.statusBarColor = getColor(R.color.neo_base)
        window.navigationBarColor = getColor(R.color.neo_base)

        val code = intent.getStringExtra(EXTRA_GROUP_CODE)
        group = ModelGroup.getByCode(code)
        val g = group
        if (g == null) {
            finish()
            return
        }

        Model.initAllModel()
        // 必须先预加载账号配置：否则字段对象是构造默认值，未开开关直接返回时
        // save() 会把默认值当成改动整份覆盖磁盘真实配置，且 UI 显示的开关状态与真实配置不一致
        ConfigPreload.prepare(currentConfigUserId())

        // 注意：Kotlin 的 g.name 会解析成 Enum.name（英文常量），必须走显式 getter 才能取到中文名
        findViewById<TextView>(R.id.neo_group_title).text = ModelGroup.getName(g.code) ?: g.code
        findViewById<View>(R.id.neo_group_back).setOnClickListener { saveAndFinish() }
        findViewById<View>(R.id.neo_group_execute).setOnClickListener {
            try {
                val i = Intent("com.eg.android.AlipayGphone.sesame.execute")
                i.putExtra("group", g.code)
                sendBroadcast(i)
                Toast.makeText(this, "已发送执行请求：${g.name}", Toast.LENGTH_SHORT).show()
            } catch (t: Throwable) {
                Toast.makeText(this, "执行失败：${t.message}", Toast.LENGTH_SHORT).show()
            }
        }

        val searchInput = findViewById<EditText>(R.id.neo_group_search_input)
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                currentQuery = s?.toString() ?: ""
                rebuildRows()
            }
        })
        findViewById<View>(R.id.neo_group_search_close).setOnClickListener {
            searchInput.text.clear()
            rebuildRows()
        }

        buildRows()
    }

    override fun onBackPressed() {
        saveAndFinish()
    }

    override fun onResume() {
        super.onResume()
        // 从四级页(SELECT/SELECT_AND_COUNT 编辑)返回时父字段可能已变化，
        // 立即重算依赖字段可见性，无需返回重进页面
        rebuildRows()
    }

    /** 保存字段改动并通知支付宝进程重载；无改动直接返回，不保存不广播。 */
    private fun saveAndFinish() {
        try {
            // hasFieldChanges 依赖 ConfigPreload 已加载（valueBaseline 非空），
            // 未加载时恒为 false，因此必须保证 onCreate 里先 prepare 再进本方法。
            if (ConfigV2.hasFieldChanges() && ConfigV2.save(currentConfigUserId(), false)) {
                // 保存配置只重新初始化、不触发任务执行（reinit 广播）
                sendBroadcast(Intent("com.eg.android.AlipayGphone.sesame.reinit"))
                modified = false
            }
        } catch (t: Throwable) {
            Toast.makeText(this, "保存失败：${t.message}", Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** 配置读写跟随账号页选中的账号（与主界面恢复逻辑同一存储）；未选账号时回退默认配置。 */
    private fun currentConfigUserId(): String? {
        val last = getSharedPreferences("sesame_ui_state", Context.MODE_PRIVATE)
            .getString("last_selected_user_id", null)
        return if (last.isNullOrEmpty()) null else last
    }

    /** 重建配置项列表（搜索过滤后用）；保留滚动位置，避免重建后列表跳回顶部。 */
    private fun rebuildRows() {
        // 取消待执行的防抖重建，避免重复重建
        rebuildHandler.removeCallbacks(rebuildRunnable)
        val scroll = findViewById<ScrollView>(R.id.neo_group_scroll)
        val prevY = scroll.scrollY
        findViewById<LinearLayout>(R.id.neo_group_container).removeAllViews()
        buildRows()
        if (prevY > 0) {
            scroll.post { scroll.scrollTo(0, prevY) }
        }
    }

    private val rebuildHandler = Handler(Looper.getMainLooper())
    private val rebuildRunnable = Runnable { rebuildRows() }

    /** 开关点击后的防抖重建：合并快速连点，仅最后一下触发全列表重建。 */
    private fun scheduleRebuild() {
        rebuildHandler.removeCallbacks(rebuildRunnable)
        rebuildHandler.postDelayed(rebuildRunnable, 120L)
    }

    override fun onDestroy() {
        super.onDestroy()
        rebuildHandler.removeCallbacks(rebuildRunnable)
    }

    /** 三级页搜索匹配：字段名、编码或描述包含关键字（忽略大小写），与四级页一致 */
    private fun matchesSearch(query: String, field: ModelField<*>): Boolean {
        return field.name?.contains(query, ignoreCase = true) == true
                || field.code.contains(query, ignoreCase = true)
                || field.description?.contains(query, ignoreCase = true) == true
    }

    private fun buildRows() {
        val g = group ?: return
        val container = findViewById<LinearLayout>(R.id.neo_group_container)
        val marginPx = dp(12)
        val topPx = dp(14)
        val query = currentQuery.trim()

        // 会员组并入"其他"页：会员排第一，动物竞猜等 OTHER 项紧随其后
        // 农场组并入"庄园"页：庄园配置后追加农场配置
        val renderGroups = when (g) {
            ModelGroup.OTHER -> listOf(ModelGroup.MEMBER, ModelGroup.OTHER)
            ModelGroup.FARM -> listOf(ModelGroup.FARM, ModelGroup.ORCHARD)
            else -> listOf(g)
        }
        renderGroups.forEach { rg ->
            Model.getGroupModelConfig(rg).values.forEach { mc ->
            val visibleFields = mc.fields.values.filter {
                it.isVisible(mc) && (query.isBlank() || matchesSearch(query, it))
            }
            if (visibleFields.isEmpty()) {
                return@forEach
            }
            // 顶层改成"模型折叠卡"：默认全部折叠，头部直接显示 已开/总数，
            // 展开后按字段名里已有的"模块|字段"前缀拆小节，避免几十个开关平铺
            val body = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), 0, dp(14), dp(6))
                visibility = if (query.isNotBlank() || expandedModels[mc.code] == true) {
                    View.VISIBLE
                } else {
                    View.GONE
                }
            }
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = ContextCompat.getDrawable(this@NeoGroupFieldsActivity, R.drawable.neu_card_raised)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, marginPx) }
            }
            card.addView(buildModelHeader(mc, visibleFields, body))
            card.addView(body)
            container.addView(card)

            var lastSection: String? = null
            var firstRow = true
            visibleFields.forEach { field ->
                val raw = field.name ?: ""
                val section = if (raw.contains("|")) {
                    raw.substringBefore("|").trim().takeIf { it.isNotEmpty() }
                } else {
                    null
                }
                if (section != null && section != lastSection) {
                    body.addView(buildSectionTitle(section))
                    firstRow = true
                }
                lastSection = section
                if (!firstRow) body.addView(buildRowDivider())
                firstRow = false
                body.addView(buildFieldRow(mc, field, 0, rg))
                if (rg == ModelGroup.BASE && field.code == "debugMode") {
                    body.addView(buildRowDivider())
                    body.addView(
                        buildAppConfigRow(
                            "开启状态栏禁删",
                            0,
                            getter = { AppConfig.INSTANCE.enableOnGoing ?: false },
                            setter = { v -> AppConfig.INSTANCE.enableOnGoing = v }
                        )
                    )
                    body.addView(buildRowDivider())
                    body.addView(
                        buildAppConfigRow(
                            "屏蔽部分弹窗",
                            0,
                            getter = { AppConfig.INSTANCE.closeCaptchaDialog ?: true },
                            setter = { v -> AppConfig.INSTANCE.closeCaptchaDialog = v }
                        )
                    )
                    body.addView(buildRowDivider())
                    body.addView(
                        buildAppConfigRow(
                            "气泡提示",
                            0,
                            getter = { AppConfig.INSTANCE.showToast ?: true },
                            setter = { v -> AppConfig.INSTANCE.showToast = v }
                        )
                    )
                    body.addView(buildRowDivider())
                    body.addView(buildToastOffsetRow(0))
                }
            }
            }
        }
    }

    /** 构建字段条目卡片；返回条目 View，供点击后刷新自身状态。 */
    private fun buildFieldRow(mc: ModelConfig, field: ModelField<*>, marginPx: Int, ownerGroup: ModelGroup): View {
        // 紧凑单行：名称占左、当前值与状态贴右，不再一行一张大圆角卡（密度提升的主要来源）
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(11), 0, dp(11))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, marginPx) }
        }
        val nameView = TextView(this).apply {
            textSize = 14f
            setTextColor(ContextCompat.getColor(this@NeoGroupFieldsActivity, R.color.neo_text_primary))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val summaryView = TextView(this).apply {
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@NeoGroupFieldsActivity, R.color.neo_text_hint))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.END
            maxWidth = dp(150)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, dp(8), 0) }
        }
        val statusView = TextView(this).apply {
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(12), dp(4), dp(12), dp(4))
            background = ContextCompat.getDrawable(this@NeoGroupFieldsActivity, R.drawable.neu_pill)
            setTextColor(ContextCompat.getColor(this@NeoGroupFieldsActivity, R.color.neo_text_hint))
            visibility = View.GONE
        }
        val arrowView = TextView(this).apply {
            text = "›"
            textSize = 20f
            setTextColor(ContextCompat.getColor(this@NeoGroupFieldsActivity, R.color.neo_text_hint))
            setPadding(dp(4), 0, 0, 0)
            visibility = View.GONE
        }
        row.addView(nameView)
        row.addView(summaryView)
        row.addView(statusView)
        row.addView(arrowView)

        // 名称里"模块|字段"的前缀已提升为小节标题，行内只留后半段
        val rawName = field.name ?: ""
        nameView.text = if (rawName.contains("|")) {
            rawName.substringAfter("|").trim().ifBlank { rawName.substringBefore("|").trim() }
        } else {
            rawName
        }
        summaryView.text = field.description

        when (field.type) {
            "BOOLEAN" -> {
                arrowView.visibility = View.GONE
                statusView.visibility = View.VISIBLE
                updateBooleanStatus(statusView, field.value as? Boolean ?: false)
                row.setOnClickListener {
                    val next = !(field.value as? Boolean ?: false)
                    field.setObjectValue(next)
                    updateBooleanStatus(statusView, next)
                    modified = true
                    // 该开关可能是其他字段的父依赖：延迟合并重建列表让依赖字段即时显示/隐藏，
                    // 快速连点只重建一次，避免全列表反复 inflate（卡顿根因之一）
                    scheduleRebuild()
                }
            }

            "INTEGER", "MULTIPLY_INTEGER" -> {
                arrowView.visibility = View.VISIBLE
                statusView.visibility = View.GONE
                val imf = field as? IntegerModelField
                val min = imf?.minLimit
                val max = imf?.maxLimit
                val hint = when {
                    min == null && max == null -> ""
                    min != null && min < 0 -> "（-1 表示按最大额度）"
                    min != null && max != null -> "（${min}~${max}）"
                    max != null -> "（上限 ${max}）"
                    else -> "（下限 ${min}）"
                }
                summaryView.text = field.configValue + hint
                row.setOnClickListener {
                    showEditDialog(
                        title = field.name ?: "",
                        initial = field.configValue ?: "",
                        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED,
                        multiLine = false,
                    ) { input ->
                        val filtered = input.filterIndexed { index, c -> c.isDigit() || (c == '-' && index == 0) }
                        val parsed = filtered.toIntOrNull()
                        val belowMin = min != null && (parsed == null || parsed < min)
                        val aboveMax = max != null && (parsed == null || parsed > max)
                        if (parsed == null || belowMin || aboveMax) {
                            Toast.makeText(this, "请输入合法数值", Toast.LENGTH_SHORT).show()
                            return@showEditDialog
                        }
                        field.setConfigValue(filtered)
                        summaryView.text = field.configValue + hint
                        modified = true
                    }
                }
            }

            "STRING", "TEXT" -> {
                arrowView.visibility = View.VISIBLE
                statusView.visibility = View.GONE
                summaryView.text = field.configValue
                row.setOnClickListener {
                    if (field.code == "memberPointExchangeCustom") {
                        showCustomExchangeDialog(field.name ?: "", field.configValue ?: "") { input ->
                            field.setConfigValue(input)
                            summaryView.text = field.configValue
                            modified = true
                        }
                    } else {
                        showEditDialog(
                            title = field.name ?: "",
                            initial = field.configValue ?: "",
                            inputType = InputType.TYPE_CLASS_TEXT,
                            multiLine = field.type == "TEXT",
                        ) { input ->
                            field.setConfigValue(input)
                            summaryView.text = field.configValue
                            modified = true
                        }
                    }
                }
            }

            "LIST" -> {
                arrowView.visibility = View.VISIBLE
                statusView.visibility = View.GONE
                val list = (field.value as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
                val timeKind = detectTimeKind(field)
                if (timeKind != TimeFieldKind.NONE) {
                    // 时间类字段不再让用户手填 "065530,2359"，改成滚轮
                    summaryView.text = summarizeTimeList(list, timeKind)
                    row.setOnClickListener {
                        val apply: (List<String>) -> Unit = { newList ->
                            field.setObjectValue(newList)
                            summaryView.text = summarizeTimeList(newList, timeKind)
                            modified = true
                        }
                        if (timeKind == TimeFieldKind.POINTS) {
                            showTimePointDialog(field.name ?: "", list, apply)
                        } else {
                            showTimeRangeDialog(field.name ?: "", list, apply)
                        }
                    }
                } else {
                    summaryView.text = list.joinToString(",")
                    row.setOnClickListener {
                        showEditDialog(
                            title = field.name ?: "",
                            initial = list.joinToString("\n"),
                            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
                            multiLine = true,
                        ) { input ->
                            val newList = input.lines().map { it.trim() }.filter { it.isNotEmpty() }
                            field.setObjectValue(newList)
                            summaryView.text = newList.joinToString(",")
                            modified = true
                        }
                    }
                }
            }

            "CHOICE" -> {
                arrowView.visibility = View.VISIBLE
                statusView.visibility = View.GONE
                val cmf = field as? ChoiceModelField
                val options = cmf?.expandKey ?: emptyArray()
                val current = field.value as? Int ?: 0
                summaryView.text = options.getOrNull(current) ?: "未选择"
                row.setOnClickListener {
                    showChoiceDialog(field.name ?: "", options, current) { index ->
                        field.setObjectValue(index)
                        summaryView.text = options.getOrNull(index) ?: "未选择"
                        modified = true
                    }
                }
            }

            "READ_TEXT", "URL_TEXT" -> {
                arrowView.visibility = View.GONE
                statusView.visibility = View.GONE
                summaryView.text = field.configValue
                row.setOnClickListener { Toast.makeText(this, "只读配置", Toast.LENGTH_SHORT).show() }
            }

            "SELECT", "SELECT_ONE", "SELECT_AND_COUNT", "SELECT_AND_COUNT_ONE" -> {
                arrowView.visibility = View.VISIBLE
                statusView.visibility = View.GONE
                summaryView.text = "点击编辑"
                row.setOnClickListener {
                    startActivity(
                        Intent(this, NeoSelectionEditActivity::class.java).apply {
                            putExtra(NeoSelectionEditActivity.EXTRA_GROUP_CODE, ownerGroup.code)
                            putExtra(NeoSelectionEditActivity.EXTRA_FIELD_CODE, field.code)
                            putExtra(NeoSelectionEditActivity.EXTRA_MODEL_CODE, mc.code)
                        }
                    )
                }
            }

            "EMPTY" -> {
                arrowView.visibility = View.VISIBLE
                statusView.visibility = View.GONE
                summaryView.text = null
                row.setOnClickListener {
                    (field as? EmptyModelField)?.clickRunner?.run()
                }
            }

            else -> {
                arrowView.visibility = View.GONE
                statusView.visibility = View.GONE
                row.setOnClickListener { Toast.makeText(this, "暂不支持该类型", Toast.LENGTH_SHORT).show() }
            }
        }
        return row
    }

    /** 布尔开关胶囊：开=主色软底青字，关=灰凹陷。 */
    private fun updateBooleanStatus(status: TextView, on: Boolean) {
        if (on) {
            status.text = "开"
            status.background = ContextCompat.getDrawable(this, R.drawable.neu_pill_on)
            status.setTextColor(ContextCompat.getColor(this, R.color.neo_primary))
        } else {
            status.text = "关"
            status.background = ContextCompat.getDrawable(this, R.drawable.neu_pill)
            status.setTextColor(ContextCompat.getColor(this, R.color.neo_text_hint))
        }
    }

    /** AppConfig 全局布尔开关行：即时写 AppConfig 并落盘 + 广播重载。 */
    private fun buildAppConfigRow(title: String, marginPx: Int, getter: () -> Boolean, setter: (Boolean) -> Unit): View {
        val row = layoutInflater.inflate(R.layout.neo_item_field, null)
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.setMargins(0, 0, 0, marginPx)
        row.layoutParams = lp

        val nameView = row.findViewById<TextView>(R.id.neo_field_name)
        val summaryView = row.findViewById<TextView>(R.id.neo_field_summary)
        val statusView = row.findViewById<TextView>(R.id.neo_field_status)
        val arrowView = row.findViewById<TextView>(R.id.neo_field_arrow)

        nameView.text = title
        summaryView.text = null
        arrowView.visibility = View.GONE
        statusView.visibility = View.VISIBLE
        updateBooleanStatus(statusView, getter())
        row.setOnClickListener {
            val next = !getter()
            setter(next)
            AppConfig.saveAsync()
            sendBroadcast(Intent("com.eg.android.AlipayGphone.sesame.reloadConfig"))
            updateBooleanStatus(statusView, next)
        }
        return row
    }

    /** AppConfig 气泡纵向偏移：数值输入行。 */
    private fun buildToastOffsetRow(marginPx: Int): View {
        val row = layoutInflater.inflate(R.layout.neo_item_field, null)
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.setMargins(0, 0, 0, marginPx)
        row.layoutParams = lp

        val nameView = row.findViewById<TextView>(R.id.neo_field_name)
        val summaryView = row.findViewById<TextView>(R.id.neo_field_summary)
        val statusView = row.findViewById<TextView>(R.id.neo_field_status)
        val arrowView = row.findViewById<TextView>(R.id.neo_field_arrow)

        nameView.text = "气泡纵向偏移"
        arrowView.visibility = View.VISIBLE
        statusView.visibility = View.GONE
        fun refresh() {
            summaryView.text = (AppConfig.INSTANCE.toastOffsetY ?: 0).toString() + " px（正数向下）"
        }
        refresh()
        row.setOnClickListener {
            showEditDialog(
                title = "气泡纵向偏移",
                initial = (AppConfig.INSTANCE.toastOffsetY ?: 0).toString(),
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED,
                multiLine = false,
            ) { input ->
                val filtered = input.filterIndexed { index, c -> c.isDigit() || (c == '-' && index == 0) }
                val parsed = filtered.toIntOrNull()
                if (parsed == null) {
                    Toast.makeText(this, "请输入合法数值", Toast.LENGTH_SHORT).show()
                    return@showEditDialog
                }
                AppConfig.INSTANCE.toastOffsetY = parsed
                AppConfig.saveAsync()
                sendBroadcast(Intent("com.eg.android.AlipayGphone.sesame.reloadConfig"))
                refresh()
            }
        }
        return row
    }

    /** 额外兑换增强弹窗：输入框 + 权益库候选列表（输入实时过滤，点选追加名称，仍可手动输入）。 */
    private fun showCustomExchangeDialog(title: String, initial: String, onOk: (String) -> Unit) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.neo_dialog_field_edit)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setCanceledOnTouchOutside(true)
        val width = resources.displayMetrics.widthPixels - dp(64)
        dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)

        dialog.findViewById<TextView>(R.id.neo_edit_title).text = title

        val content = dialog.findViewById<FrameLayout>(R.id.neo_edit_content)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val edit = EditText(this).apply {
            setText(initial)
            setTextColor(ContextCompat.getColor(this@NeoGroupFieldsActivity, R.color.neo_text_primary))
            setTextSize(14f)
            setBackgroundResource(R.drawable.neu_input_bg)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
            hint = "输入权益名称（多个用逗号分隔），或从下方列表选择"
        }
        root.addView(
            edit,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        val listScroll = ScrollView(this).apply {
            setBackgroundResource(R.drawable.neu_card_raised)
        }
        val listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        listScroll.addView(
            listContainer,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        val scrollLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(240))
        scrollLp.topMargin = dp(12)
        root.addView(listScroll, scrollLp)

        fun refreshCandidates() {
            listContainer.removeAllViews()
            val kw = edit.text?.toString()?.trim() ?: ""
            val all = MemberBenefit.getList()
            val candidates = if (kw.isEmpty()) {
                all
            } else {
                all.filter { it.name.contains(kw, ignoreCase = true) }
            }.take(100)
            if (candidates.isEmpty()) {
                val empty = TextView(this@NeoGroupFieldsActivity).apply {
                    text = if (kw.isEmpty()) "暂无权益数据，先运行一次会员任务抓取后再来" else "无匹配权益：$kw"
                    setTextColor(ContextCompat.getColor(this@NeoGroupFieldsActivity, R.color.neo_text_hint))
                    textSize = 13f
                    setPadding(dp(4), dp(8), dp(4), dp(8))
                }
                listContainer.addView(empty)
                return
            }
            candidates.forEach { ben ->
                val opt = TextView(this@NeoGroupFieldsActivity).apply {
                    val point = ben.point
                    text = if (point.isNullOrEmpty()) ben.name else "${ben.name}（${point}积分）"
                    setTextColor(ContextCompat.getColor(this@NeoGroupFieldsActivity, R.color.neo_text_primary))
                    textSize = 14f
                    setPadding(dp(8), dp(9), dp(8), dp(9))
                    background = ContextCompat.getDrawable(this@NeoGroupFieldsActivity, R.drawable.neu_input_bg)
                    val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    lp.setMargins(0, 0, 0, dp(6))
                    layoutParams = lp
                    setOnClickListener {
                        val current = edit.text?.toString() ?: ""
                        val items = current.split("[,，;；]").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
                        if (!items.contains(ben.name)) {
                            items.add(ben.name)
                            edit.setText(items.joinToString(","))
                            edit.setSelection(edit.text?.length ?: 0)
                        }
                    }
                }
                listContainer.addView(opt)
            }
        }

        edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                refreshCandidates()
            }
        })
        refreshCandidates()

        content.addView(
            root,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        dialog.findViewById<TextView>(R.id.neo_edit_cancel).setOnClickListener { dialog.dismiss() }
        dialog.findViewById<TextView>(R.id.neo_edit_ok).setOnClickListener {
            onOk(edit.text?.toString() ?: "")
            dialog.dismiss()
        }
        dialog.show()
    }

    // ==================== 模型折叠卡 / 小节 / 分隔线 ====================

    private enum class TimeFieldKind { NONE, POINTS, RANGE }

    /** 只认这三个已知时间字段，避免误伤其它 LIST（好友名单、城市码等） */
    private val timeFieldCodes = setOf("execAtTimeList", "wakenAtTimeList", "energyTime")

    /** 折叠状态按模型编码记忆；未记录即折叠（需求：全部默认折叠） */
    private val expandedModels = mutableMapOf<String, Boolean>()

    private fun detectTimeKind(field: ModelField<*>): TimeFieldKind {
        if (field.code !in timeFieldCodes) {
            return TimeFieldKind.NONE
        }
        return if (field.code == "energyTime") TimeFieldKind.RANGE else TimeFieldKind.POINTS
    }

    private fun two(v: Int): String = if (v < 10) "0$v" else v.toString()

    private fun parsePoint(raw: String): Pair<Int, Int> {
        val s = raw.trim()
        val h = s.take(2).toIntOrNull() ?: 0
        val m = s.drop(2).take(2).toIntOrNull() ?: 0
        return h.coerceIn(0, 23) to m.coerceIn(0, 59)
    }

    private fun formatPoint(raw: String): String {
        val (h, m) = parsePoint(raw)
        return two(h) + ":" + two(m)
    }

    private fun formatRange(raw: String): String {
        val parts = raw.split("-")
        if (parts.size != 2) {
            return raw
        }
        return formatPoint(parts[0]) + "-" + formatPoint(parts[1])
    }

    private fun summarizeTimeList(list: List<String>, kind: TimeFieldKind): String {
        val items = list.filter { it.isNotBlank() && it != "-1" }
        if (items.isEmpty()) {
            return "已关闭"
        }
        return if (kind == TimeFieldKind.RANGE) {
            items.joinToString("、") { formatRange(it) }
        } else {
            items.joinToString("、") { formatPoint(it) }
        }
    }

    /** 模型卡头部：状态点 + 名称 + 已开/总数 + 折叠箭头，点一下切换 */
    private fun buildModelHeader(mc: ModelConfig, fields: List<ModelField<*>>, body: LinearLayout): View {
        val boolFields = fields.filter { it.type == "BOOLEAN" }
        val onCount = boolFields.count { it.value as? Boolean == true }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            isClickable = true
            isFocusable = true
        }
        header.addView(View(this).apply {
            background = ContextCompat.getDrawable(this@NeoGroupFieldsActivity, R.drawable.neu_dot_status)
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this@NeoGroupFieldsActivity, R.color.neo_primary)
            )
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(8))
        })
        header.addView(TextView(this).apply {
            text = mc.name
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@NeoGroupFieldsActivity, R.color.neo_text_primary))
            setPadding(dp(10), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        if (boolFields.isNotEmpty()) {
            header.addView(TextView(this).apply {
                text = "$onCount/${boolFields.size} 开"
                textSize = 12f
                setTextColor(ContextCompat.getColor(this@NeoGroupFieldsActivity, R.color.neo_text_hint))
                setPadding(0, 0, dp(10), 0)
            })
        }
        val chev = TextView(this).apply {
            text = if (body.visibility == View.VISIBLE) "▾" else "▸"
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@NeoGroupFieldsActivity, R.color.neo_text_hint))
        }
        header.addView(chev)
        header.setOnClickListener {
            val expand = body.visibility != View.VISIBLE
            body.visibility = if (expand) View.VISIBLE else View.GONE
            chev.text = if (expand) "▾" else "▸"
            expandedModels[mc.code] = expand
        }
        return header
    }

    private fun buildSectionTitle(text: String): View {
        return TextView(this).apply {
            this.text = text
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@NeoGroupFieldsActivity, R.color.neo_text_hint))
            setPadding(0, dp(12), 0, dp(2))
        }
    }

    private fun buildRowDivider(): View {
        return View(this).apply {
            setBackgroundColor(Color.parseColor("#F1F5FB"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                Math.max(1, dp(1))
            )
        }
    }

    // ==================== 时间选择器 ====================

    /** 按住连续加减：按下先执行一次，380ms 后每 70ms 重复一次（满足"长按连续"） */
    private fun bindRepeat(view: View, action: () -> Unit) {
        val handler = Handler(Looper.getMainLooper())
        val runner = object : Runnable {
            override fun run() {
                action()
                handler.postDelayed(this, 70L)
            }
        }
        view.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    action()
                    handler.postDelayed(runner, 380L)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(runner)
                    true
                }
                else -> false
            }
        }
    }

    /** 单个"▲ 数值 ▼ 单位"滚轮格 */
    private fun createStepper(unit: String, initial: Int, mod: Int, onChange: (Int) -> Unit): LinearLayout {
        var value = initial
        val num = TextView(this).apply {
            text = two(value)
            textSize = 26f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@NeoGroupFieldsActivity, R.color.neo_text_primary))
            gravity = Gravity.CENTER
        }
        fun step(delta: Int) {
            value = ((value + delta) % mod + mod) % mod
            num.text = two(value)
            onChange(value)
        }
        val up = TextView(this).apply {
            text = "▲"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(this@NeoGroupFieldsActivity, R.color.neo_text_hint))
            setPadding(dp(16), dp(2), dp(16), dp(2))
        }
        val down = TextView(this).apply {
            text = "▼"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(this@NeoGroupFieldsActivity, R.color.neo_text_hint))
            setPadding(dp(16), dp(2), dp(16), dp(2))
        }
        bindRepeat(up) { step(1) }
        bindRepeat(down) { step(-1) }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = ContextCompat.getDrawable(this@NeoGroupFieldsActivity, R.drawable.neu_card_raised)
            setPadding(dp(2), dp(6), dp(2), dp(4))
            addView(up)
            addView(num)
            addView(down)
            addView(TextView(this@NeoGroupFieldsActivity).apply {
                text = unit
                textSize = 10f
                gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(this@NeoGroupFieldsActivity, R.color.neo_text_hint))
            })
            layoutParams = LinearLayout.LayoutParams(dp(56), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    /** 开关胶囊行：左侧标题、右侧 开/关 */
    private fun createToggleRow(title: String, desc: String?, initial: Boolean, onChanged: (Boolean) -> Unit): View {
        var on = initial
        val pill = TextView(this).apply {
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(12), dp(4), dp(12), dp(4))
        }
        fun renderPill() {
            pill.text = if (on) "开" else "关"
            pill.background = ContextCompat.getDrawable(
                this@NeoGroupFieldsActivity,
                if (on) R.drawable.neu_pill_on else R.drawable.neu_pill
            )
            pill.setTextColor(
                ContextCompat.getColor(
                    this@NeoGroupFieldsActivity,
                    if (on) R.color.neo_primary else R.color.neo_text_hint
                )
            )
        }
        renderPill()
        pill.isClickable = true
        pill.setOnClickListener {
            on = !on
            renderPill()
            onChanged(on)
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = ContextCompat.getDrawable(this@NeoGroupFieldsActivity, R.drawable.neu_card_raised)
            isClickable = true
        }
        val textBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        textBox.addView(TextView(this).apply {
            text = title
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@NeoGroupFieldsActivity, R.color.neo_text_primary))
        })
        if (!desc.isNullOrBlank()) {
            textBox.addView(TextView(this).apply {
                text = desc
                textSize = 11f
                setTextColor(ContextCompat.getColor(this@NeoGroupFieldsActivity, R.color.neo_text_hint))
            })
        }
        row.addView(textBox)
        row.addView(pill)
        row.setOnClickListener { pill.performClick() }
        return row
    }

    private fun createDialogShell(title: String): Pair<Dialog, FrameLayout> {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.neo_dialog_field_edit)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.setLayout(
            resources.displayMetrics.widthPixels - dp(64),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.findViewById<TextView>(R.id.neo_edit_title).text = title
        return dialog to dialog.findViewById(R.id.neo_edit_content)
    }

    /** 时间点选择器：一天中的若干时刻，关闭时写回 -1 */
    private fun showTimePointDialog(title: String, current: List<String>, onOk: (List<String>) -> Unit) {
        val (dialog, content) = createDialogShell(title)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        var enabled = !current.contains("-1")
        val points = current.filter { it != "-1" }.map { parsePoint(it) }.toMutableList()
        if (points.isEmpty()) {
            points.add(6 to 55)
            points.add(23 to 59)
        }

        root.addView(
            createToggleRow("启用定时", "关掉后按「执行间隔」跑", enabled) { enabled = it },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        val listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        fun rebuildPoints() {
            listBox.removeAllViews()
            points.forEachIndexed { idx, hm ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(8), dp(10), dp(8))
                    background = ContextCompat.getDrawable(this@NeoGroupFieldsActivity, R.drawable.neu_card_raised)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(0, 0, 0, dp(8)) }
                }
                row.addView(TextView(this).apply {
                    text = "第 ${idx + 1} 个"
                    textSize = 12f
                    setTextColor(ContextCompat.getColor(this@NeoGroupFieldsActivity, R.color.neo_text_hint))
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                row.addView(createStepper("时", hm.first, 24) { v -> points[idx] = v to points[idx].second })
                row.addView(TextView(this).apply {
                    text = ":"
                    textSize = 18f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(ContextCompat.getColor(this@NeoGroupFieldsActivity, R.color.neo_text_hint))
                    setPadding(dp(2), 0, dp(2), 0)
                })
                row.addView(createStepper("分", hm.second, 60) { v -> points[idx] = points[idx].first to v })
                row.addView(TextView(this).apply {
                    text = "删除"
                    textSize = 12f
                    setTextColor(ContextCompat.getColor(this@NeoGroupFieldsActivity, R.color.neo_error))
                    setPadding(dp(12), dp(8), dp(4), dp(8))
                    setOnClickListener {
                        if (points.size <= 1) {
                            Toast.makeText(this@NeoGroupFieldsActivity, "至少保留一个时间点", Toast.LENGTH_SHORT).show()
                        } else {
                            points.removeAt(idx)
                            rebuildPoints()
                        }
                    }
                })
                listBox.addView(row)
            }
            val add = TextView(this).apply {
                text = "＋ 添加时间点"
                textSize = 13f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(ContextCompat.getColor(this@NeoGroupFieldsActivity, R.color.neo_primary))
                gravity = Gravity.CENTER
                setPadding(0, dp(11), 0, dp(11))
                background = ContextCompat.getDrawable(this@NeoGroupFieldsActivity, R.drawable.neu_input_bg)
                setOnClickListener {
                    if (points.size >= 6) {
                        Toast.makeText(this@NeoGroupFieldsActivity, "最多 6 个时间点", Toast.LENGTH_SHORT).show()
                    } else {
                        points.add(12 to 0)
                        rebuildPoints()
                    }
                }
            }
            listBox.addView(add, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2) })
        }
        rebuildPoints()

        val boxLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        boxLp.topMargin = dp(12)
        root.addView(listBox, boxLp)

        content.addView(root, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        dialog.findViewById<TextView>(R.id.neo_edit_cancel).setOnClickListener { dialog.dismiss() }
        dialog.findViewById<TextView>(R.id.neo_edit_ok).setOnClickListener {
            val out = if (enabled) points.map { two(it.first) + two(it.second) } else listOf("-1")
            onOk(out)
            dialog.dismiss()
        }
        dialog.show()
    }

    /** 时间段选择器：开始→结束，写回 HHMM-HHMM */
    private fun showTimeRangeDialog(title: String, current: List<String>, onOk: (List<String>) -> Unit) {
        val (dialog, content) = createDialogShell(title)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val raw = current.firstOrNull { it.contains("-") } ?: "0700-0731"
        val parts = raw.split("-")
        val start = parsePoint(parts.getOrElse(0) { "0700" })
        val end = parsePoint(parts.getOrElse(1) { "0731" })
        var sh = start.first
        var sm = start.second
        var eh = end.first
        var em = end.second

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(14), dp(10), dp(14))
            background = ContextCompat.getDrawable(this@NeoGroupFieldsActivity, R.drawable.neu_card_raised)
        }
        val center = { t: String ->
            TextView(this).apply {
                text = t
                textSize = 18f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(ContextCompat.getColor(this@NeoGroupFieldsActivity, R.color.neo_text_hint))
                setPadding(dp(3), 0, dp(3), 0)
            }
        }
        box.addView(createStepper("时", sh, 24) { sh = it })
        box.addView(center(":"))
        box.addView(createStepper("分", sm, 60) { sm = it })
        box.addView(center("→"))
        box.addView(createStepper("时", eh, 24) { eh = it })
        box.addView(center(":"))
        box.addView(createStepper("分", em, 60) { em = it })

        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(8)
        root.addView(box, lp)

        root.addView(TextView(this).apply {
            text = "只有落在这个时间段内才收能量，其他时间不动"
            textSize = 11f
            setTextColor(ContextCompat.getColor(this@NeoGroupFieldsActivity, R.color.neo_text_hint))
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        })

        content.addView(root, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        dialog.findViewById<TextView>(R.id.neo_edit_cancel).setOnClickListener { dialog.dismiss() }
        dialog.findViewById<TextView>(R.id.neo_edit_ok).setOnClickListener {
            onOk(listOf(two(sh) + two(sm) + "-" + two(eh) + two(em)))
            dialog.dismiss()
        }
        dialog.show()
    }

    /** 通用文本编辑弹窗：title + EditText + 取消/确定。 */
    private fun showEditDialog(
        title: String,
        initial: String,
        inputType: Int,
        multiLine: Boolean,
        onOk: (String) -> Unit,
    ) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.neo_dialog_field_edit)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setCanceledOnTouchOutside(true)
        val width = resources.displayMetrics.widthPixels - dp(64)
        dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)

        dialog.findViewById<TextView>(R.id.neo_edit_title).text = title
        val edit = EditText(this).apply {
            setText(initial)
            setTextColor(ContextCompat.getColor(this@NeoGroupFieldsActivity, R.color.neo_text_primary))
            setTextSize(14f)
            setBackgroundResource(R.drawable.neu_input_bg)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            this.inputType = inputType
            isSingleLine = !multiLine
            if (multiLine) {
                gravity = android.view.Gravity.TOP or android.view.Gravity.START
                minLines = 3
                maxLines = 8
            }
        }
        dialog.findViewById<FrameLayout>(R.id.neo_edit_content).addView(
            edit,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        dialog.findViewById<TextView>(R.id.neo_edit_cancel).setOnClickListener { dialog.dismiss() }
        dialog.findViewById<TextView>(R.id.neo_edit_ok).setOnClickListener {
            onOk(edit.text?.toString() ?: "")
            dialog.dismiss()
        }
        dialog.show()
    }

    /** 选项单选弹窗：title + 选项列表，点选即生效。 */
    private fun showChoiceDialog(title: String, options: Array<String>, current: Int, onPick: (Int) -> Unit) {
        if (options.isEmpty()) {
            Toast.makeText(this, "无可用选项", Toast.LENGTH_SHORT).show()
            return
        }
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.neo_dialog_field_edit)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setCanceledOnTouchOutside(true)
        val width = resources.displayMetrics.widthPixels - dp(64)
        dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)

        dialog.findViewById<TextView>(R.id.neo_edit_title).text = title
        dialog.findViewById<TextView>(R.id.neo_edit_ok).visibility = View.GONE
        dialog.findViewById<TextView>(R.id.neo_edit_cancel).setOnClickListener { dialog.dismiss() }

        val content = dialog.findViewById<FrameLayout>(R.id.neo_edit_content)
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        options.forEachIndexed { index, opt ->
            val optView = TextView(this).apply {
                text = opt
                setTextColor(
                    ContextCompat.getColor(
                        this@NeoGroupFieldsActivity,
                        if (index == current) R.color.neo_primary else R.color.neo_text_primary
                    )
                )
                textSize = 15f
                setTypeface(typeface, if (index == current) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                setPadding(dp(6), dp(10), dp(6), dp(10))
                background = ContextCompat.getDrawable(this@NeoGroupFieldsActivity, R.drawable.neu_input_bg)
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.setMargins(0, 0, 0, dp(8))
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
}
