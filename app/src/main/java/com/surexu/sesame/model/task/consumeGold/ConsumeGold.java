package com.surexu.sesame.model.task.consumeGold;

import org.json.JSONArray;
import org.json.JSONObject;
import com.surexu.sesame.data.ModelFields;
import com.surexu.sesame.data.ModelGroup;
import com.surexu.sesame.data.task.ModelTask;
import com.surexu.sesame.data.RuntimeInfo;
import com.surexu.sesame.model.base.TaskCommon;
import com.surexu.sesame.util.Log;
import com.surexu.sesame.util.Status;
import com.surexu.sesame.util.TimeUtil;

import java.util.Random;

public class ConsumeGold extends ModelTask {
    private static final String TAG = ConsumeGold.class.getSimpleName();
    /** 一轮里最多复查 2 轮任务列表，避免状态不推进时无限递归 */
    private static final int MAX_TASK_ROUNDS = 2;
    /** 单轮抽奖本地封顶，剩下的次数下一轮再抽 */
    private static final int MAX_DRAW_PER_ROUND = 5;
    private static final String RISK_FLAG = "consumeGold::riskBlocked";
    private static final Random RANDOM = new Random();

    public Boolean getDefaultEnable() {
        return true;
    }


    @Override
    public String getName() {
        return "消费金";
    }

    @Override
    public ModelGroup getGroup() {
        return ModelGroup.OTHER;
    }

    @Override
    public ModelFields getFields() {
        ModelFields modelFields = new ModelFields();
        return modelFields;
    }

    public Boolean check() {
        if (TaskCommon.IS_ENERGY_TIME) {
            return false;
        }
        long executeTime = RuntimeInfo.getInstance().getLong("consumeGold", 0);
        return System.currentTimeMillis() - executeTime >= 21600000;
    }

    public void run() {
        if (Status.hasFlagToday(RISK_FLAG)) {
            Log.other("赚消费金💰⏸️账号被风控，今日暂停");
            return;
        }
        try {
            RuntimeInfo.getInstance().put("consumeGold", System.currentTimeMillis());
            signinCalendar();
            taskV2Index("CG_TASK_LIST");
            taskV2Index("HOME_NAVIGATION");
            taskV2Index("CG_SIGNIN_AD_FEEDS");
            taskV2Index("SURPRISE_TASK");
            taskV2Index("CG_BROWSER_AD_FEEDS");
            consumeGoldIndex();
        } catch (Throwable t) {
            Log.i(TAG, "start.run err:");
            Log.printStackTrace(TAG, t);
        }
    }

    /** 请求失败 / 被风控一律不再递归；复查最多 MAX_TASK_ROUNDS 轮 */
    private void taskV2Index(String taskSceneCode) {
        taskV2Index(taskSceneCode, 0);
    }

    private void taskV2Index(String taskSceneCode, int round) {
        boolean doubleCheck = false;
        try {
            String s = ConsumeGoldRpcCall.taskV2Index(taskSceneCode);
            if (isRisk(s)) {
                return;
            }
            JSONObject jo = new JSONObject(s);
            if (jo.optBoolean("success")) {
                JSONArray taskList = jo.optJSONArray("taskList");
                if (taskList == null) {
                    return;
                }
                for (int i = 0; i < taskList.length(); i++) {
                    JSONObject item = taskList.optJSONObject(i);
                    if (item == null) {
                        continue;
                    }
                    JSONObject extInfo = item.optJSONObject("extInfo");
                    if (extInfo == null) {
                        continue;
                    }
                    String taskStatus = extInfo.optString("taskStatus", "");
                    String title = extInfo.optString("title", "");
                    String taskId = extInfo.optString("actionBizId", "");
                    if (taskId.isEmpty()) {
                        continue;
                    }
                    if ("TO_RECEIVE".equals(taskStatus)) {
                        taskV2TriggerReceive(taskId, title);
                    } else if ("NONE_SIGNUP".equals(taskStatus)) {
                        if (!taskV2TriggerSignUp(taskId)) {
                            continue;
                        }
                        TimeUtil.sleep(800L + RANDOM.nextInt(700));
                        if (taskV2TriggerSend(taskId)) {
                            doubleCheck = true;
                        }
                    } else if ("SIGNUP_COMPLETE".equals(taskStatus)) {
                        if (taskV2TriggerSend(taskId)) {
                            doubleCheck = true;
                        }
                    }
                    TimeUtil.sleep(800L + RANDOM.nextInt(700));
                }
                // 只有确实推进了任务、且没超过上限才复查一次
                if (doubleCheck && round + 1 < MAX_TASK_ROUNDS) {
                    TimeUtil.sleep(1000L + RANDOM.nextInt(1000));
                    taskV2Index(taskSceneCode, round + 1);
                }
            } else {
                Log.record(jo.optString("resultDesc", "任务列表查询失败"));
            }
        } catch (Throwable t) {
            Log.i(TAG, "taskV2Index err:");
            Log.printStackTrace(TAG, t);
        }
    }

    /** 命中 1009 / 访问被拒绝时当天停手，不再继续发请求 */
    private boolean isRisk(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        if (s.contains("1009") || s.contains("访问被拒绝") || s.contains("风险操作")) {
            Status.flagToday(RISK_FLAG);
            Log.other("赚消费金💰⏸️命中风控，今日暂停");
            return true;
        }
        return false;
    }

    private void taskV2TriggerReceive(String taskId, String name) {
        try {
            String s = ConsumeGoldRpcCall.taskV2TriggerReceive(taskId);
            JSONObject jo = new JSONObject(s);
            if (jo.optBoolean("success")) {
                int receiveAmount = jo.getInt("receiveAmount");
                Log.other("赚消费金💰[" + name + "]#" + receiveAmount);
                com.surexu.sesame.util.FeatureStats.add(getName(), "point", receiveAmount);
            }
        } catch (Throwable t) {
            Log.i(TAG, "taskV2TriggerReceive err:");
            Log.printStackTrace(TAG, t);
        }
    }

    private boolean taskV2TriggerSignUp(String taskId) {
        try {
            String s = ConsumeGoldRpcCall.taskV2TriggerSignUp(taskId);
            if (isRisk(s)) {
                return false;
            }
            return new JSONObject(s).optBoolean("success");
        } catch (Throwable t) {
            Log.i(TAG, "taskV2TriggerSignUp err:");
            Log.printStackTrace(TAG, t);
            return false;
        }
    }

    private boolean taskV2TriggerSend(String taskId) {
        try {
            String s = ConsumeGoldRpcCall.taskV2TriggerSend(taskId);
            if (isRisk(s)) {
                return false;
            }
            return new JSONObject(s).optBoolean("success");
        } catch (Throwable t) {
            Log.i(TAG, "taskV2TriggerSend err:");
            Log.printStackTrace(TAG, t);
            return false;
        }
    }

    private void consumeGoldIndex() {
        try {
            String s = ConsumeGoldRpcCall.consumeGoldIndex();
            JSONObject jo = new JSONObject(s);
            if (jo.optBoolean("success")) {
                JSONObject homePromoInfoDTO = jo.getJSONObject("homePromoInfoDTO");
                JSONArray homePromoTokenDTOList = homePromoInfoDTO.getJSONArray("homePromoTokenDTOList");
                int tokenLeftAmount = 0;
                int tokenTotalAmount = 0;
                for (int i = 0; i < homePromoTokenDTOList.length(); i++) {
                    jo = homePromoTokenDTOList.getJSONObject(i);
                    String tokenType = jo.getString("tokenType");
                    if ("CONSUME_GOLD".equals(tokenType)) {
                        tokenLeftAmount = jo.getInt("tokenLeftAmount");
                    }
                }
                if (tokenLeftAmount > 0) {
                    int draws = Math.min(tokenLeftAmount, MAX_DRAW_PER_ROUND);
                    if (tokenLeftAmount > draws) {
                        Log.other("赚消费金💰本轮先抽 " + draws + " 次，剩余 " + (tokenLeftAmount - draws) + " 次下轮再抽");
                    }
                    for (int j = 0; j < draws; j++) {
                        String draw = ConsumeGoldRpcCall.promoTrigger();
                        if (isRisk(draw)) {
                            break;
                        }
                        jo = new JSONObject(draw);
                        if (!jo.optBoolean("success")) {
                            break;
                        }
                        JSONObject homePromoPrizeInfoDTO = jo.optJSONObject("homePromoPrizeInfoDTO");
                        if (homePromoPrizeInfoDTO == null) {
                            break;
                        }
                        Log.other("赚消费金💰[投5币抽]#" + homePromoPrizeInfoDTO.optInt("quantity", 0));
                        JSONObject promoAdvertisementInfo = homePromoPrizeInfoDTO
                                .optJSONObject("promoAdvertisementInfo");
                        if (promoAdvertisementInfo != null) {
                            String outBizNo = promoAdvertisementInfo.optString("outBizNo", "");
                            if (!outBizNo.isEmpty()) {
                                isRisk(ConsumeGoldRpcCall.advertisement(outBizNo));
                            }
                        }
                        TimeUtil.sleep(1000L + RANDOM.nextInt(1000));
                    }
                }
            }
        } catch (Throwable t) {
            Log.i(TAG, "queryTreasureBox err:");
            Log.printStackTrace(TAG, t);
        }
    }

    private void signinCalendar() {
        try {
            String s = ConsumeGoldRpcCall.signinCalendar();
            JSONObject jo = new JSONObject(s);
            if (jo.optBoolean("success")) {
                boolean signed = jo.getBoolean("isSignInToday");
                if (!signed) {
                    jo = new JSONObject(ConsumeGoldRpcCall.openBoxAward());
                    if (jo.optBoolean("success")) {
                        int amount = jo.getInt("amount");
                        Log.other("消费金签到💰[" + amount + "金币]");
                    }
                }
            }
        } catch (Throwable t) {
            Log.i(TAG, "signinCalendar err:");
            Log.printStackTrace(TAG, t);
        }
    }
}
