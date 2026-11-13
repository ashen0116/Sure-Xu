package com.surexu.sesame.model.task.huabeiGold;

import com.surexu.sesame.data.ModelFields;
import com.surexu.sesame.data.modelFieldExt.BooleanModelField;
import com.surexu.sesame.model.common.gamePlay.GamePlaySimulator;
import com.surexu.sesame.model.task.rewardSupport.IsolatedRewardTask;
import com.surexu.sesame.util.FeatureStats;
import com.surexu.sesame.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * 花呗金：每日签到 + 余额记录。
 * <p>
 * 只做「签到」「查余额」这类零风险动作；开通、充值、分期、购买等真实交易类任务一律不碰。
 * <p>
 * 接口（抓包确认，均为 needle / MTop 方法）：
 * <ul>
 *   <li>查询签到：com.alipay.pcreditrecweb.needle.hbjQuerySignInfo，返回 todaySignIn / playId / notAllow</li>
 *   <li>签到：com.alipay.pcreditrecweb.needle.hbjSignIn，入参 playId，返回 rewardInfos[].price</li>
 *   <li>余额：com.alipay.pcreditbfweb.huabeijinNew.index.queryDetailByScene，返回 data.certInfo.availablePoint</li>
 * </ul>
 */
public final class HuabeiGold extends IsolatedRewardTask {

    private static final String RPC_SIGN_INFO = "com.alipay.pcreditrecweb.needle.hbjQuerySignInfo";
    private static final String RPC_SIGN_IN = "com.alipay.pcreditrecweb.needle.hbjSignIn";
    private static final String RPC_DETAIL = "com.alipay.pcreditbfweb.huabeijinNew.index.queryDetailByScene";
    /** 任务列表：入参只要 requestFrom + scene，返回 data 数组 */
    private static final String RPC_TASK_QUERY = "com.alipay.pcreditbfweb.sdk.task.queryV2";
    /** 任务触发：同一个 outBizNo 依次发 signup / send 两个 stage 即算完成 */
    private static final String RPC_TASK_TRIGGER = "com.alipay.pcreditbfweb.sdk.task.trigger";
    private static final String TASK_SCENE = "NUGGETS_HUABEI_GOLDEN_POINT";
    /** 逛一逛型任务的标记（按钮显示「去逛逛」）；另一类是 CLICK_JUMP（要真实交易），不碰 */
    private static final String TRIGGER_BROWSE = "CLICK_TRIGGER";
    private static final int MAX_BROWSE_PER_ROUND = 10;
    /** 单轮最多翻几张（每翻一张要查一次状态，受整轮请求上限约束） */
    private static final int MAX_FLOP_PER_ROUND = 12;

    /* ===== 花花卡（花呗金页面里的「秋日趣翻卡」活动） ===== */
    /** 花花卡页面状态：卡位、字卡、剩余翻卡次数 */
    private static final String RPC_HHK_INDEX = "com.alipay.pcreditbfweb.promo.hhk.index";
    /** 翻一张卡 */
    private static final String RPC_HHK_FLOP = "com.alipay.pcreditbfweb.promo.hhk.index.flopcard";
    private static final String HHK_SCENE = "HUA_HUA_CARD";
    private static final String HHK_PRODUCT = "HUA_HUA_CARD_NORMAL_23Y06";
    private static final String HHK_SCENE_FLOP = "CARD_HUA_HUA_CARD";
    private static final String HHK_PRODUCT_FLOP = "CARD_HUA_HUA_CARD_23Y06";
    /** 活动资格凭证：2026-11-08 抓包实测值，活动换期失效时会记日志并跳过 */
    private static final String HHK_CERT_ID = "CT464128086";
    /** 活动玩法 ID（花花卡单人赛） */
    private static final String HHK_PLAY_ID = "HHK_SINGLE_CAMP_1";
    /** 活动起止时间，抓包实测值；换期后接口会返回失败，只记日志不影响其它功能 */
    private static final long HHK_CAMP_START = 1792944000000L;
    private static final long HHK_CAMP_END = 1795607999000L;
    /** 玩法进度查询：返回 nodeList（3张花花卡 / 3个花呗金 / 5个花呗金 三档奖励） */
    private static final String RPC_HHK_CONSULT = "com.alipay.pcreditbfweb.drpc.collect.campConsult";
    /** 按 nodeId 领奖 */
    private static final String RPC_HHK_TRIGGER = "com.alipay.pcreditbfweb.drpc.collect.campTrigger";
    /** 集齐五种字卡后合成开奖 */
    private static final String RPC_HHK_MERGE = "com.alipay.pcreditbfweb.promo.hhk.index.merge";
    /** 查合成抽到的奖品（campId + outBizNo） */
    private static final String RPC_HHK_PRIZE = "com.alipay.pcreditbfweb.drpc.pageQueryPrizeSendOrderLite";

    private BooleanModelField checkIn;
    private BooleanModelField showBalance;
    private BooleanModelField browseTasks;
    private BooleanModelField autoGamePlay;
    private BooleanModelField huaHuaCard;
    private BooleanModelField hhkDoTasks;
    private BooleanModelField hhkFlopCard;
    private BooleanModelField hhkClaimReward;
    private BooleanModelField hhkMerge;

    @Override
    public String getName() {
        return "花呗金";
    }

    @Override
    public Boolean getDefaultEnable() {
        return true;
    }

    @Override
    protected void addFields(ModelFields modelFields) {
        checkIn = new BooleanModelField("checkIn", "每日签到", true);
        modelFields.addField(checkIn);
        showBalance = new BooleanModelField("showBalance", "记录余额", true);
        modelFields.addField(showBalance);
        BooleanModelField browse = new BooleanModelField("browseTasks", "完成逛一逛任务", true);
        this.browseTasks = browse;
        modelFields.addField(browse);
        autoGamePlay = new BooleanModelField("autoGamePlay", "自动假玩游戏任务", true);
        autoGamePlay.setDescription("任务里要玩小游戏时，自动上报游玩时长，不用真开游戏");
        modelFields.addField(autoGamePlay);

        BooleanModelField hhk = new BooleanModelField("huaHuaCard", "花花卡 | 总开关", true);
        this.huaHuaCard = hhk;
        modelFields.addField(hhk);
        BooleanModelField hhkTask = new BooleanModelField("hhkDoTasks", "花花卡 | 做任务赚卡", true).setDependsOn("huaHuaCard");
        this.hhkDoTasks = hhkTask;
        modelFields.addField(hhkTask);
        BooleanModelField hhkFlop = new BooleanModelField("hhkFlopCard", "花花卡 | 自动翻卡", true).setDependsOn("huaHuaCard");
        this.hhkFlopCard = hhkFlop;
        modelFields.addField(hhkFlop);
        BooleanModelField hhkClaim = new BooleanModelField("hhkClaimReward", "花花卡 | 领累计任务奖励", true).setDependsOn("huaHuaCard");
        this.hhkClaimReward = hhkClaim;
        modelFields.addField(hhkClaim);
        BooleanModelField hhkMergeField = new BooleanModelField("hhkMerge", "花花卡 | 集齐自动合成", true).setDependsOn("huaHuaCard");
        this.hhkMerge = hhkMergeField;
        modelFields.addField(hhkMergeField);
    }

    @Override
    protected void execute(Run run) throws Exception {
        JSONObject signInfo = run.query(RPC_SIGN_INFO, "[{\"callRisk\":true}]").optJSONObject("result");
        if (signInfo == null) {
            Log.record(getName() + "：签到信息为空，停止本轮");
            return;
        }
        if (signInfo.optBoolean("notAllow", false)) {
            Log.record(getName() + "：当前账号暂不支持签到");
        } else if (signInfo.optBoolean("todaySignIn", false)) {
            Log.record(getName() + "：今日已签到（连续 " + signInfo.optInt("continuousDays") + " 天）");
        } else if (!checkIn.getValue().booleanValue()) {
            Log.record(getName() + "：今日未签到，签到开关已关闭");
        } else {
            String playId = signInfo.optString("playId", "");
            if (playId.isEmpty()) {
                Log.record(getName() + "：缺少 playId，停止本轮");
                return;
            }
            JSONObject resp = run.onceToday("signIn", RPC_SIGN_IN,
                    "[{\"callRisk\":true,\"playId\":\"" + playId + "\"}]", new Allowed() {
                        @Override
                        public boolean isAllowed() {
                            return checkIn.getValue().booleanValue();
                        }
                    });
            if (resp != null) {
                int gained = 0;
                JSONObject result = resp.optJSONObject("result");
                JSONArray rewards = result == null ? null : result.optJSONArray("rewardInfos");
                if (rewards != null) {
                    for (int i = 0; i < rewards.length(); i++) {
                        JSONObject reward = rewards.optJSONObject(i);
                        if (reward != null) {
                            gained += reward.optInt("price", 0);
                        }
                    }
                }
                Log.record(getName() + "：签到成功，获得 " + gained + " 个花呗金");
                FeatureStats.add("花呗金", "sign", 1);
                if (gained > 0) {
                    FeatureStats.add("花呗金", "gold", gained);
                }
            }
        }

        if (showBalance.getValue().booleanValue()) {
            JSONObject detail = run.query(RPC_DETAIL,
                    "[{\"account\":1,\"clearRedDot\":true,\"exchangeQuery\":true,\"queryDebtBalance\":true}]");
            JSONObject data = detail.optJSONObject("data");
            JSONObject cert = data == null ? null : data.optJSONObject("certInfo");
            if (cert != null) {
                String point = cert.optString("availablePoint", "");
                JSONObject rate = cert.optJSONObject("pointExchangeMoneyRate");
                String money = rate == null ? "" : rate.optString("exchangeMoney", "");
                Log.record(getName() + "：当前余额 " + point + " 个花呗金" + (money.isEmpty() ? "" : "（约 " + money + " 元）"));
            }
        }

        if (browseTasks != null && browseTasks.getValue().booleanValue()) {
            doBrowseTasks(run);
        }

        if (huaHuaCard != null && huaHuaCard.getValue().booleanValue()) {
            doHuaHuaCard(run);
        }
    }

    /* ==================== 花花卡 ==================== */

    /**
     * 花花卡（花呗金 - 秋日趣翻卡）：先做「去逛逛」任务赚卡，再把没翻的卡翻掉。
     * <p>
     * 2026-11-08 真机抓包确认的链路：
     * <ul>
     *   <li>任务列表：task.queryV2，scene=HUA_HUA_CARD</li>
     *   <li>完成：task.trigger，signup → send，taskCenId 与花呗金不同（AP1377876）</li>
     *   <li>页面状态：promo.hhk.index，返回 cardPrizes（卡位是否翻开）和 remainingTimes（还能翻几张）</li>
     *   <li>翻卡：promo.hhk.index.flopcard，lineIndex 就是卡位序号（从 0 开始）</li>
     * </ul>
     */
    private void doHuaHuaCard(Run run) throws Exception {
        if (hhkDoTasks != null && hhkDoTasks.getValue().booleanValue()) {
            doHuaHuaCardTasks(run);
        }
        if (hhkClaimReward != null && hhkClaimReward.getValue().booleanValue()) {
            claimHuaHuaCardRewards(run);
        }
        if (hhkFlopCard != null && hhkFlopCard.getValue().booleanValue()) {
            flopHuaHuaCard(run);
        }
        if (hhkMerge != null && hhkMerge.getValue().booleanValue()) {
            mergeHuaHuaCard(run);
        }
    }

    /**
     * 集齐「花呗翻字卡」五种各一张后合成开奖。
     * <p>
     * 抓包实测：hhk.index.merge 只带 productCode + sceneCode；
     * 返回 bizNo / campId，再用 pageQueryPrizeSendOrderLite 查这次抽到什么。
     */
    private void mergeHuaHuaCard(Run run) throws Exception {
        JSONObject data = huaHuaCardState(run);
        if (data == null) {
            return;
        }
        JSONArray fragments = data.optJSONArray("fragments");
        if (fragments == null || fragments.length() == 0) {
            return;
        }
        boolean ready = true;
        StringBuilder lack = new StringBuilder();
        for (int i = 0; i < fragments.length(); i++) {
            JSONObject f = fragments.optJSONObject(i);
            if (f == null) {
                continue;
            }
            if (f.optInt("number", 0) <= 0) {
                ready = false;
                lack.append(f.optString("charDesc", "?"));
            }
        }
        if (!ready) {
            Log.record(getName() + "：花花卡还差[" + lack + "]，暂不合成");
            return;
        }
        String mergeData = "[{\"productCode\":\"" + HHK_PRODUCT + "\",\"sceneCode\":\"" + HHK_SCENE + "\"}]";
        JSONObject resp = run.tryQuery(RPC_HHK_MERGE, mergeData);
        JSONObject result = resp == null ? null : resp.optJSONObject("data");
        if (result == null) {
            Log.record(getName() + "：花花卡合成失败（接口没有返回结果）");
            return;
        }
        String campId = result.optString("campId", "");
        String bizNo = result.optString("bizNo", "");
        String prizeName = "";
        if (!campId.isEmpty() && !bizNo.isEmpty()) {
            String prizeData = "[{\"args\":{\"campIds\":[\"" + campId + "\"],\"outBizNo\":\"" + bizNo
                    + "\",\"pageNum\":1,\"perPageSize\":10}}]";
            JSONObject prizeResp = run.tryQuery(RPC_HHK_PRIZE, prizeData);
            prizeName = pickPrizeName(prizeResp);
        }
        Log.record(getName() + "：花花卡合成开奖" + (prizeName.isEmpty() ? "成功" : "，抽到[" + prizeName + "]"));
        int gold = pickGoldAmount(prizeName);
        if (gold > 0) {
            FeatureStats.add("花呗金", "gold", gold);
        }
    }

    /** 从奖品接口里取出奖品展示名 */
    private String pickPrizeName(JSONObject resp) {
        try {
            JSONObject root = resp == null ? null : resp.optJSONObject("data");
            JSONObject result = root == null ? null : root.optJSONObject("result");
            JSONObject resultData = result == null ? null : result.optJSONObject("resultData");
            JSONArray list = resultData == null ? null : resultData.optJSONArray("dataList");
            if (list == null || list.length() == 0) {
                return "";
            }
            JSONObject first = list.optJSONObject(0);
            JSONObject ext = first == null ? null : first.optJSONObject("extInfo");
            return ext == null ? "" : ext.optString("PRIZE_DISPLAY_NAME", "");
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** "8个花呗金" → 8；不是花呗金就返回 0 */
    private int pickGoldAmount(String prizeName) {
        if (prizeName == null || !prizeName.contains("花呗金")) {
            return 0;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)").matcher(prizeName);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    /** 统一的 hhk.index 查询，取 data 节点 */
    private JSONObject huaHuaCardState(Run run) throws Exception {
        String indexData = "[{\"certId\":\"" + HHK_CERT_ID + "\",\"productCode\":\"" + HHK_PRODUCT
                + "\",\"productCodeFlop\":\"" + HHK_PRODUCT_FLOP
                + "\",\"sceneCode\":\"" + HHK_SCENE
                + "\",\"sceneCodeFlop\":\"" + HHK_SCENE_FLOP + "\"}]";
        JSONObject resp = run.tryQuery(RPC_HHK_INDEX, indexData);
        return resp == null ? null : resp.optJSONObject("data");
    }

    /**
     * 领「累计任务奖励」：完成 3 / 7 / 10 个任务分别给 3 张卡、3 个花呗金、5 个花呗金。
     * <p>
     * 抓包实测：campConsult 返回 nodeList，每个节点带 status；
     * status=RECEIVE 表示已领过，DISABLE 表示没达成，其它值就是可以领，用 campTrigger 带 nodeId 领取。
     */
    private void claimHuaHuaCardRewards(Run run) throws Exception {
        String consult = "[{\"args\":{\"endTime\":" + HHK_CAMP_END
                + ",\"playId\":\"" + HHK_PLAY_ID + "\",\"requestFrom\":\"pccp\""
                + ",\"startTime\":" + HHK_CAMP_START + "}}]";
        JSONObject resp = run.tryQuery(RPC_HHK_CONSULT, consult);
        // 实测返回是 data.result.result.nodeList，这里逐层往里找，找到为止
        JSONObject result = resp == null ? null : resp.optJSONObject("data");
        for (int depth = 0; depth < 3 && result != null && result.optJSONArray("nodeList") == null; depth++) {
            result = result.optJSONObject("result");
        }
        JSONArray nodes = result == null ? null : result.optJSONArray("nodeList");
        if (nodes == null || nodes.length() == 0) {
            Log.record(getName() + "：花花卡累计奖励取不到，本轮跳过领奖");
            return;
        }
        int claimed = 0;
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject node = nodes.optJSONObject(i);
            if (node == null) {
                continue;
            }
            String status = node.optString("status", "");
            if ("DISABLE".equals(status) || "RECEIVE".equals(status) || status.isEmpty()) {
                continue;
            }
            JSONObject config = node.optJSONObject("config");
            String nodeId = config == null ? "" : config.optString("id", "");
            String title = node.optString("title", nodeId);
            if (nodeId.isEmpty()) {
                continue;
            }
            String trigger = "[{\"args\":{\"endTime\":" + HHK_CAMP_END
                    + ",\"nodeId\":\"" + nodeId + "\",\"playId\":\"" + HHK_PLAY_ID
                    + "\",\"requestFrom\":\"pccp\",\"startTime\":" + HHK_CAMP_START + "}}]";
            JSONObject r = run.tryQuery(RPC_HHK_TRIGGER, trigger);
            if (r != null && Boolean.TRUE.equals(r.opt("success"))) {
                claimed++;
                Log.record(getName() + "：领到花花卡累计奖励[" + title + "]");
            }
        }
        if (claimed == 0) {
            Log.record(getName() + "：花花卡累计奖励暂无可领取的档位");
        }
    }

    /** 做「赚花花卡」里标记为去逛逛的任务（CLICK_TRIGGER），交易类固定跳过 */
    private void doHuaHuaCardTasks(Run run) throws Exception {
        JSONObject resp = run.tryQuery(RPC_TASK_QUERY,
                "[{\"requestFrom\":\"pccp\",\"scene\":\"" + HHK_SCENE + "\"}]");
        JSONArray list = resp == null ? null : resp.optJSONArray("data");
        if (list == null || list.length() == 0) {
            Log.record(getName() + "：花花卡没取到任务列表，本轮跳过赚卡");
            return;
        }
        int done = 0;
        int skipped = 0;
        for (int i = 0; i < list.length(); i++) {
            JSONObject task = list.optJSONObject(i);
            if (task == null) {
                continue;
            }
            if (!TRIGGER_BROWSE.equals(task.optString("taskTriggerType"))) {
                skipped++;
                continue;
            }
            String status = task.optString("taskStatus", "");
            if (status.startsWith("COMPLETE") || status.startsWith("FINISH")) {
                skipped++;
                continue;
            }
            String taskId = task.optString("taskId", "");
            String cenId = task.optString("taskCenId", "");
            if (taskId.isEmpty() || cenId.isEmpty()) {
                skipped++;
                continue;
            }
            JSONObject show = task.optJSONObject("taskShowInfo");
            String title = show == null ? taskId : show.optString("title", taskId);
            int reward = task.optInt("rewardExp", 0);
            String outBizNo = taskId + "299012"
                    + String.format(Locale.ROOT, "%02d", 10 + (int) ((System.currentTimeMillis() / 1000L) % 90L));
            trigger(run, taskId, cenId, outBizNo, "signup", false);
            if (!trigger(run, taskId, cenId, outBizNo, "send", false)) {
                skipped++;
                continue;
            }
            done++;
            Log.record(getName() + "：花花卡完成[" + title + "]，+" + reward + " 张");
        }
        Log.record(getName() + "：花花卡赚卡完成 " + done + " 个，跳过 " + skipped + " 个（交易类不做）");
    }

    /**
     * 自动翻卡：查剩余次数，把没翻的卡位一张张翻掉。
     * <p>
     * 卡位是循环的 —— 翻完 3 个会自动补 3 个新的，所以每翻一张都重新查一次状态，
     * 一直翻到次数为 0 为止；另外设了上限，防止接口异常时空转。
     */
    private void flopHuaHuaCard(Run run) throws Exception {
        int flopCount = 0;
        int emptyRounds = 0;
        while (flopCount < MAX_FLOP_PER_ROUND) {
            JSONObject data = huaHuaCardState(run);
            if (data == null) {
                Log.record(getName() + "：花花卡页面取不到（活动凭证可能已换期），本轮跳过翻卡");
                return;
            }
            int remaining = data.optInt("remainingTimes", 0);
            if (remaining <= 0) {
                break;
            }
            JSONArray cards = data.optJSONArray("cardPrizes");
            int index = -1;
            if (cards != null) {
                for (int i = 0; i < cards.length(); i++) {
                    JSONObject card = cards.optJSONObject(i);
                    if (card == null || card.optBoolean("isOpen", false)) {
                        continue;
                    }
                    JSONObject pos = card.optJSONObject("position");
                    index = pos == null ? i : pos.optInt("index", i);
                    break;
                }
            }
            if (index < 0) {
                // 这一批翻完了，新卡位可能还在刷，最多再等两轮
                emptyRounds++;
                if (emptyRounds > 2) {
                    Log.record(getName() + "：花花卡暂无可翻卡位（还剩 " + remaining + " 次）");
                    break;
                }
                Thread.sleep(1500L);
                continue;
            }
            emptyRounds = 0;
            String flopData = "[{\"isNewPageBegin\":false,\"lineIndex\":" + index
                    + ",\"productCode\":\"" + HHK_PRODUCT_FLOP
                    + "\",\"sceneCode\":\"" + HHK_SCENE_FLOP + "\"}]";
            JSONObject r = run.tryQuery(RPC_HHK_FLOP, flopData);
            JSONObject rData = r == null ? null : r.optJSONObject("data");
            if (rData == null) {
                Log.record(getName() + "：花花卡第 " + (index + 1) + " 张翻卡失败，本轮停止翻卡");
                break;
            }
            flopCount++;
            FeatureStats.add("花呗金", "hhk", 1);
            String prize = "";
            JSONArray prizes = rData.optJSONArray("playPrizeList");
            if (prizes != null && prizes.length() > 0) {
                JSONObject p = prizes.optJSONObject(0);
                if (p != null) {
                    prize = p.optString("prizeName", "");
                }
            }
            Log.record(getName() + "：翻开花花卡第 " + (index + 1) + " 张" + (prize.isEmpty() ? "" : "，得到[" + prize + "]"));
        }
        if (flopCount > 0) {
            Log.record(getName() + "：本轮共翻 " + flopCount + " 张花花卡");
        }
    }

    /**
     * 逛一逛任务：拉任务列表，只挑「去逛逛」（CLICK_TRIGGER）的，逐个先 signup 再 send。
     * <p>
     * 2026-11-08 在真机上抓包实测：签完 send 奖励立即到账（余额 20 → 25），不需要真的停留在落地页；
     * 实测两个任务号 AP19374853 / AP10372354 都成功。
     * <p>
     * 另一类 CLICK_JUMP（开通话费自动充 / 打车 / 借呗 / 保险体验 / 旧衣回收）必须真实交易，
     * 一律跳过，绝不自动触碰。
     */
    private void doBrowseTasks(Run run) throws Exception {
        // 用试探式：拿不到列表只跳过逛一逛，不影响签到/查余额的结果，也能把失败原因写进日志
        JSONObject resp = run.tryQuery(RPC_TASK_QUERY,
                "[{\"requestFrom\":\"pccp\",\"scene\":\"" + TASK_SCENE + "\"}]");
        JSONArray list = resp == null ? null : resp.optJSONArray("data");
        if (list == null || list.length() == 0) {
            Log.record(getName() + "：没取到任务列表，本轮跳过逛一逛");
            return;
        }
        int done = 0;
        int skipped = 0;
        int gamePlayed = 0;
        for (int i = 0; i < list.length() && done < MAX_BROWSE_PER_ROUND; i++) {
            JSONObject task = list.optJSONObject(i);
            if (task == null) {
                continue;
            }
            String triggerType = task.optString("taskTriggerType", "");
            JSONObject show = task.optJSONObject("taskShowInfo");
            String taskId = task.optString("taskId", "");
            String title = show == null ? taskId : show.optString("title", taskId);
            // 真实交易类一律不碰；但标题里带「游戏 / 试玩」的可能是小游戏任务，留给下面的假玩分支
            if ("CLICK_JUMP".equals(triggerType) && !isGameTitle(title)) {
                skipped++;
                continue;
            }
            boolean game = isAutoGamePlay() && GamePlaySimulator.isGameTask(task)
                    && (!TRIGGER_BROWSE.equals(triggerType) || isGameTitle(title));
            if (!TRIGGER_BROWSE.equals(triggerType) && !game) {
                skipped++;
                continue;
            }
            String status = task.optString("taskStatus", "");
            if (status.startsWith("COMPLETE") || status.startsWith("FINISH")) {
                skipped++;
                continue;
            }
            String cenId = task.optString("taskCenId", "");
            if (taskId.isEmpty() || cenId.isEmpty()) {
                skipped++;
                continue;
            }
            int reward = task.optInt("rewardExp", 0);
            if (game) {
                if (GamePlaySimulator.playForTask(getName(), task,
                        GamePlaySimulator.Scene.GENERIC, gamePlayed)) {
                    gamePlayed++;
                    Log.record(getName() + "：🎮游戏任务已上报游玩[" + title + "]，继续复核任务状态");
                } else {
                    Log.record(getName() + "：⏭️游戏任务[" + title
                            + "]缺游戏id或本轮已满（日志里有字段，抓包后可补）");
                }
            }
            // outBizNo 是幂等键，抓包实测形如「任务号 + 299012 + 两位序号」，这里按秒数取两位保证每次不重复
            String outBizNo = taskId + "299012"
                    + String.format(Locale.ROOT, "%02d", 10 + (int) ((System.currentTimeMillis() / 1000L) % 90L));
            // 已报名的任务 signup 会失败，所以只看 send 的结果
            trigger(run, taskId, cenId, outBizNo, "signup", true);
            if (!trigger(run, taskId, cenId, outBizNo, "send", true)) {
                skipped++;
                continue;
            }
            done++;
            Log.record(getName() + "：完成逛一逛[" + title + "]，+" + reward + " 花呗金");
            FeatureStats.add("花呗金", "gold", reward);
            FeatureStats.add("花呗金", "point", reward);
        }
        Log.record(getName() + "：本轮逛一逛完成 " + done + " 个（假玩 " + gamePlayed
                + " 个游戏任务），跳过 " + skipped + " 个（交易类不做）");
    }

    private boolean isAutoGamePlay() {
        return autoGamePlay == null || Boolean.TRUE.equals(autoGamePlay.getValue());
    }

    private boolean isGameTitle(String title) {
        return title != null && (title.contains("游戏") || title.contains("试玩")
                || title.contains("玩一玩") || title.contains("小游戏"));
    }

    /**
     * 发一次任务触发请求；stage 是 signup 还是 send。
     *
     * @param withExtras 花呗金那边实测带 extInfo/needleParam，花花卡那边实测不带，按各自抓到的格式发
     */
    private boolean trigger(Run run, String taskId, String cenId, String outBizNo, String stage, boolean withExtras)
            throws Exception {
        String data = "[{\"appletId\":\"" + taskId + "\""
                + (withExtras ? ",\"extInfo\":{},\"needleParam\":{}" : "")
                + ",\"outBizNo\":\"" + outBizNo + "\",\"retryFlag\":true"
                + ",\"stageCode\":\"" + stage + "\",\"taskCenId\":\"" + cenId + "\"}]";
        JSONObject resp = run.tryQuery(RPC_TASK_TRIGGER, data);
        return resp != null && Boolean.TRUE.equals(resp.opt("data"));
    }
}
