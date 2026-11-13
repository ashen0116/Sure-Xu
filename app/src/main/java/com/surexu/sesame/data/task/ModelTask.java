package com.surexu.sesame.data.task;

import static com.surexu.sesame.model.normal.base.BaseModel.taskRpcRequest;

import com.surexu.sesame.data.ModelGroup;
import com.surexu.sesame.util.FileUtil;
import com.surexu.sesame.util.RunProgress;
import com.surexu.sesame.util.Status;
import com.surexu.sesame.util.idMap.UserIdMap;
import lombok.Getter;
import com.surexu.sesame.data.Model;
import com.surexu.sesame.data.ModelFields;
import com.surexu.sesame.data.ModelGroup;
import com.surexu.sesame.data.ModelType;
import com.surexu.sesame.model.normal.base.BaseModel;
import com.surexu.sesame.util.FeatureStats;
import com.surexu.sesame.util.Log;
import com.surexu.sesame.util.NotificationUtil;
import com.surexu.sesame.util.RunGeneration;
import com.surexu.sesame.util.StringUtil;
import com.surexu.sesame.util.TaskCancelledException;
import com.surexu.sesame.util.TimeUtil;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public abstract class ModelTask extends Model {

    private static final Map<ModelTask, Thread> MAIN_TASK_MAP = new ConcurrentHashMap<>();

    /** 是否所有主任务均已结束（自动切号冻结前置条件：任务空闲才允许切号） */
    public static boolean isAllTaskIdle() {
        return MAIN_TASK_MAP.isEmpty();
    }

    /** 本模型的代际编号：stopTask 递增，旧代在下一个检查点作废退出，无需 join 阻塞调用方 */
    private volatile long generation = 0;

    /** 执行槽（只限本实例；重载后是新对象，跨实例靠代际令牌在检查点退出）：抢不到即放弃，不用实例锁 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    private static final int MODEL_COUNT = Math.max(getModelArray().length, 1);

    /** 整轮任务还在逐个提交中；提交期间不能用「线程池空了」判定整轮结束 */
    private static volatile boolean roundSubmitting = false;
    /** 本轮日志序号与总项数：让「执行开始/结束」能看出第几项、共几项 */
    private static final java.util.concurrent.atomic.AtomicInteger ROUND_SEQ =
            new java.util.concurrent.atomic.AtomicInteger(0);
    private static volatile int roundTotal = 0;

    /**
     * 主任务线程池：每个模型占一条线程（core = 模型数），上限 2 倍模型数，饱和后回退到调用线程执行。
     * <p>SynchronousQueue 不排队，故不存在「撤销已提交但尚未开始的那一轮」这种操作。
     */
    private static final ThreadPoolExecutor MAIN_THREAD_POOL = new ThreadPoolExecutor(MODEL_COUNT, MODEL_COUNT * 2, 30L, TimeUnit.SECONDS, new SynchronousQueue<>(), new ThreadPoolExecutor.CallerRunsPolicy());

    private final Map<String, ChildModelTask> childTaskMap = new ConcurrentHashMap<>();

    private ChildTaskExecutor childTaskExecutor;

    @Getter
    private final Runnable mainRunnable = new Runnable() {

        private final ModelTask task = ModelTask.this;

        @Override
        public void run() {
            // 执行槽已由 startTask 抢住；此处只取快照，stopTask 递增后本代作废
            long myGen = task.generation;
            MAIN_TASK_MAP.put(task, Thread.currentThread());
            // 绑定本代令牌：本线程的 sleep / RPC 入口据此感知作废
            RunGeneration prevGen = RunGeneration.bind(myGen, () -> task.generation);
            // 静音计数分层：记下上层残留，本轮只统计自己这段
            int droppedPrev = Log.takeDroppedStaleLogCount();
            // 与执行槽配对：只有真正拿到槽的线程才计入 runningCount
            NotificationUtil.trackTaskStart();
            // 每次任务起跑都刷新通知，显示当前正在执行的任务名（并行时以最新起跑的为准）
            NotificationUtil.setStatusTextExec(task.getName());
            ModelGroup group = task.getGroup();
            String groupKey = progressGroup(group);
            String prevModule = Log.beginModule(group == null ? null : group.getCode());
            int seq = ROUND_SEQ.incrementAndGet();
            Log.record("▶ 第 " + seq + "/" + roundTotal + " 项｜执行开始-" + task.getName());
            // 首页 6 大类进度条：这里才是功能真正起跑的时刻
            publishProgress("RUNNING", groupKey, task.getName(), null);
            // 首页「其他功能」用：记下每个功能最后一次起跑时间，模型无需各自埋点
            FeatureStats.touch(task.getName());
            Log.startModuleLogCount();
            Log.resetUnresolved();
            try {
                task.run();
            } catch (TaskCancelledException e) {
                Log.record(task.getName() + "⏹代际已作废，本轮提前结束");
            } catch (Exception e) {
                Log.printStackTrace(e);
            } finally {
                // 作废期间被静音的错误日志条数一并报告，避免真故障无声丢失
                int dropped = Log.takeDroppedStaleLogCount();
                if (dropped > 0) {
                    Log.record(task.getName() + "⏹代际已作废，其间静音错误日志 " + dropped + " 条");
                }
                Log.restoreDroppedStaleLogCount(droppedPrev);
                // 还原而非清空：isSync 会在同一线程嵌套起跑，清掉会抹掉外层令牌
                RunGeneration.restore(prevGen);
                // 模块收尾汇总：按「是否还有未处理任务」与「本轮是否有动作」给状态
                Log.moduleSummary(task.getName()
                        + (Log.isUnresolved() ? "⏳仍有待完成或待领取任务"
                        : (Log.stopModuleLogCount() == 0 ? "✅本轮无操作" : "✅本轮完成")));
                Log.record("执行结束-" + task.getName());
                // 首页 6 大类进度条：功能真正跑完了才 +1
                publishProgress("ITEM_DONE", groupKey, task.getName(), null);
                // 首页「其他功能」要能区分「今日已跑 / 中途断开」，把本轮结果落盘
                FeatureStats.finish(task.getName(), Log.isUnresolved() ? "partial" : "done");
                Log.endModule(prevModule);
                // 身份化移除：只删本线程登记的那条，避免旧代收尾误删新一代条目
                MAIN_TASK_MAP.remove(task, Thread.currentThread());
                // 提交完毕且再无任务在跑＝整轮真正结束，首页状态才能从「进行中」变成「已完成」
                if (!roundSubmitting && MAIN_TASK_MAP.isEmpty()) {
                    publishProgress("DONE", "", "", null);
                    Log.record("✅ 本轮全部结束｜共 " + roundTotal + " 项");
                }
                task.running.set(false);
                NotificationUtil.trackTaskEnd();
                if (NotificationUtil.getRunningCount() == 0) {
                    NotificationUtil.updateLastExecText();
                }
            }
        }

    };

    public ModelTask() {
    }

    @Override
    public final void prepare() {
        childTaskExecutor = newTimedTaskExecutor();
    }

    public String getId() {
        return toString();
    }

    public ModelType getType() {
        return ModelType.TASK;
    }

    public abstract String getName();

    public abstract ModelFields getFields();

    public abstract Boolean check();

    public Boolean isSync() {
        return false;
    }

    public abstract void run();

    public Boolean hasChildTask(String childId) {
        return childTaskMap.containsKey(childId);
    }

    public ChildModelTask getChildTask(String childId) {
        return childTaskMap.get(childId);
    }

    public Boolean addChildTask(ChildModelTask childTask) {
        String childId = childTask.getId();
        childTask.modelTask = this;
        // 提交放锁外：池满时 CallerRunsPolicy 会让提交线程就地跑完整段子任务，放进 compute 会卡住整桶
        if (!childTaskExecutor.addChildTask(childTask)) {
            return false;
        }
        ChildModelTask oldTask = childTaskMap.put(childId, childTask);
        if (oldTask != null) {
            oldTask.cancel();
        }
        return true;
    }

    public void removeChildTask(String childId) {
        ChildModelTask childTask = childTaskMap.remove(childId);
        if (childTask != null) {
            // 通知执行器放锁外，别在 CHM 桶锁内调用可能阻塞的代码
            childTaskExecutor.removeChildTask(childTask);
        }
    }

    public Integer countChildTask() {
        return childTaskMap.size();
    }

    public Boolean startTask() {
        return startTask(false);
    }

    /**
     * 启动一轮。不加 {@code synchronized}：互斥由 {@code running} 执行槽（CAS）保证，被挡住的只是执行
     * 线程自己，不像实例锁那样把整轮时间压给 {@link #stopTask()} 的调用方（宿主 onDestroy 在主线程）。
     * <p>抢槽在本方法内完成，故返回值如实反映「是否真的开始跑」：抢不到返回 false。
     */
    public Boolean startTask(Boolean force) {
        if (force) {
            stopTask();
        }
        try {
            if (!isEnable() || !check()) {
                return false;
            }
        } catch (Exception e) {
            Log.printStackTrace(e);
            return false;
        }
        if (!running.compareAndSet(false, true)) {
            Log.record(getName() + "⏭本实例上一代未退出，本轮跳过");
            return false;
        }
        try {
            if (isSync()) {
                mainRunnable.run();
            } else {
                try {
                    MAIN_THREAD_POOL.execute(mainRunnable);
                } catch (Throwable t) {
                    // 没能提交就要归还槽，否则该模型再也起不来
                    running.set(false);
                    throw t;
                }
            }
            return true;
        } catch (Exception e) {
            Log.printStackTrace(e);
            return false;
        }
    }

    public synchronized void stopTask() {
        // 递增代际：旧代在下一个检查点自行退出，无需 join 阻塞调用方。
        // 令牌只覆盖模型主线程；子任务线程未绑定，其取消仍依赖 cancel() 与 clearAllChildTask()
        generation++;
        for (ChildModelTask childModelTask : childTaskMap.values()) {
            try {
                childModelTask.cancel();
            } catch (Exception e) {
                Log.printStackTrace(e);
            }
        }
        if (childTaskExecutor != null) {
            childTaskExecutor.clearAllChildTask();
        }
        childTaskMap.clear();
        // 身份化移除：只删自己登记的那条线程，避免误删新一代条目
        Thread running = MAIN_TASK_MAP.get(this);
        if (running != null) {
            MAIN_TASK_MAP.remove(this, running);
        }
    }

    public static void startAllTask() {
        startAllTask(false);
    }

    /** 首页进度条的 5 大类：「基础」常年 0/0 已去掉，基础功能并入「其他」 */
    public static final String[] PROGRESS_GROUPS = {"FOREST", "FARM", "STALL", "SPORTS", "OTHER"};

    /** 模块有 9 个分组，合并成首页的 5 大类：农场并入庄园、基础/会员/金豆并入其他 */
    private static String progressGroup(ModelGroup g) {
        if (g == null) {
            return "OTHER";
        }
        switch (g) {
            case ORCHARD:
            case FARM:
                return "FARM";
            case MEMBER:
            case GOLDENBEANS:
            case OTHER:
            case BASE:
                return "OTHER";
            case FOREST:
                return "FOREST";
            case STALL:
                return "STALL";
            case SPORTS:
                return "SPORTS";
            default:
                return "OTHER";
        }
    }

    /** 进度统一交给账本：先落盘再广播，界面读盘兜底，丢广播也不会卡住 */
    private static void publishProgress(String event, String group, String name, String groupTotals) {
        try {
            RunProgress.publish(event, group, name, groupTotals);
        } catch (Throwable ignored) {
        }
    }

    /** 整轮开始时数一下 5 大类各有多少个开启的功能，格式 FOREST:5,FARM:8,... */
    private static String countGroups() {
        int[] counts = new int[PROGRESS_GROUPS.length];
        for (Model model : getModelArray()) {
            if (model == null || ModelType.TASK != model.getType()) {
                continue;
            }
            ModelTask t = (ModelTask) model;
            if (Boolean.FALSE.equals(t.isEnable())) {
                continue;
            }
            String g = progressGroup(t.getGroup());
            for (int i = 0; i < PROGRESS_GROUPS.length; i++) {
                if (PROGRESS_GROUPS[i].equals(g)) {
                    counts[i]++;
                    break;
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < PROGRESS_GROUPS.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(PROGRESS_GROUPS[i]).append(':').append(counts[i]);
        }
        return sb.toString();
    }

    public static void startAllTask(Boolean force) {
        // 整轮开始：清零本轮收取能量。单分组执行（startGroupTask）不算整轮，故不在此清零
        NotificationUtil.startRound();
        //自动触发备份配置文件
        if (!Status.hasFlagToday("Config::backup")) {
            FileUtil.backupConfigV2WithRolling(UserIdMap.getCurrentUid());
            Status.flagToday("Config::backup");
        }
        //执行BaseModel中自定义执行请求
        taskRpcRequest();
        // 只发一次「整轮开始」；真实进度由每个功能起跑 / 收尾时上报。
        // 不能再在提交线程池后立刻 +1，那样进度条一瞬间全满，实际任务还在后台跑。
        publishProgress("START", "", "", countGroups());
        // 本轮要跑哪些功能：先整份报给账本，首页就能列出清单和每一项的状态
        StringBuilder plan = new StringBuilder();
        int planCount = 0;
        for (Model model : getModelArray()) {
            if (model == null || ModelType.TASK != model.getType()) {
                continue;
            }
            ModelTask t = (ModelTask) model;
            if (Boolean.FALSE.equals(t.isEnable())) {
                continue;
            }
            if (plan.length() > 0) {
                plan.append('|');
            }
            plan.append(t.getName());
            planCount++;
        }
        publishProgress("PLAN", "", "", plan.toString());
        ROUND_SEQ.set(0);
        roundTotal = planCount;
        Log.record("▶ 本轮开始｜共 " + planCount + " 项");
        roundSubmitting = true;
        for (Model model : getModelArray()) {
            // 已停止就不再启动后续模型，否则 MAIN_TASK 会把整轮跑完
            if (RunGeneration.isStale()) {
                roundSubmitting = false;
                Log.record("⏹已停止，本轮不再启动后续任务");
                publishProgress("STOPPED", "", "", null);
                return;
            }
            if (model != null) {
                if (ModelType.TASK == model.getType()) {
                    ModelTask task = (ModelTask) model;
                    if (!task.startTask(force)) {
                        // 只有「本来开着、却没跑起来」的功能才扣总数。
                        // 关掉的功能压根没算进预估数，跟着扣会把整组扣成 0/0（实测就这么翻车了）
                        if (Boolean.TRUE.equals(task.isEnable())) {
                            publishProgress("SKIP", progressGroup(task.getGroup()), task.getName(), null);
                        }
                    }
                    // 带代际检查的睡眠：停止时在下一个边界结束整轮（未绑定时同 Thread.sleep）
                    try {
                        TimeUtil.sleep(1500);
                    } catch (TaskCancelledException e) {
                        roundSubmitting = false;
                        publishProgress("STOPPED", "", "", null);
                        return;
                    }
                }
            }
        }
        roundSubmitting = false;
        if (isAllTaskIdle()) {
            publishProgress("DONE", "", "", null);
        }
    }

    /**
     * 只执行指定分组下的任务（{@code groupCode} 取 {@link ModelGroup#getCode()}）。
     * <p>配置页的"执行"按钮必须通过广播交给<strong>注入进程</strong>跑：模块 App 自己的进程没有
     * libxposed 类（{@code ApplicationHook}/{@code hook.Toast}/{@code NotificationUtil} 一碰就是
     * NoClassDefFoundError），而且那是个 UI 进程，把任务循环压在主线程上会直接卡死界面。
     *
     * @return 实际触发的任务数
     */
    public static int startGroupTask(String groupCode) {
        int count = 0;
        // 分组执行也开新一轮：清掉上一轮清单，把本组要跑的功能列出来
        publishProgress("START", "", "", null);
        StringBuilder groupPlan = new StringBuilder();
        for (Model m : getModelArray()) {
            if (m == null || ModelType.TASK != m.getType()) {
                continue;
            }
            ModelGroup mg = m.getGroup();
            if (mg == null || !groupCode.equals(mg.getCode())) {
                continue;
            }
            if (Boolean.FALSE.equals(((ModelTask) m).isEnable())) {
                continue;
            }
            if (groupPlan.length() > 0) {
                groupPlan.append('|');
            }
            groupPlan.append(m.getName());
        }
        String planText = groupPlan.toString();
        publishProgress("PLAN", "", "", planText);
        roundTotal = planText.isEmpty() ? 0 : planText.split("\\|").length;
        ROUND_SEQ.set(0);
        Log.record("▶ 本轮开始｜分组共 " + roundTotal + " 项");
        roundSubmitting = true;
        for (Model model : getModelArray()) {
            if (model == null || ModelType.TASK != model.getType()) {
                continue;
            }
            ModelGroup group = model.getGroup();
            if (group == null || !groupCode.equals(group.getCode())) {
                continue;
            }
            if (((ModelTask) model).startTask(false)) {
                count++;
            }
        }
        if (count > 0) {
            // 手动执行分组也算新一轮：先清零本轮收取能量，再起跑
            NotificationUtil.startRound();
        }
        roundSubmitting = false;
        if (isAllTaskIdle()) {
            publishProgress("DONE", "", "", null);
        }
        return count;
    }

    public static void stopAllTask() {
        for (Model model : getModelArray()) {
            if (model != null) {
                try {
                    if (ModelType.TASK == model.getType()) {
                        ((ModelTask) model).stopTask();
                    }
                } catch (Exception e) {
                    Log.printStackTrace(e);
                }
            }
        }
    }

    private ChildTaskExecutor newTimedTaskExecutor() {
        ChildTaskExecutor childTaskExecutor;
        Integer timedTaskModel = BaseModel.getTimedTaskModel().getValue();
        if (timedTaskModel == BaseModel.TimedTaskModel.SYSTEM) {
            childTaskExecutor = new SystemChildTaskExecutor();
        } else if (timedTaskModel == BaseModel.TimedTaskModel.PROGRAM) {
            childTaskExecutor = new ProgramChildTaskExecutor();
        } else {
            throw new RuntimeException("not found childTaskExecutor");
        }
        return childTaskExecutor;
    }

    public static class ChildModelTask implements Runnable {

        @Getter
        private ModelTask modelTask;

        @Getter
        private final String id;

        @Getter
        private final String group;

        private final Runnable runnable;

        @Getter
        private final Long execTime;

        private CancelTask cancelTask;

        @Getter
        private Boolean isCancel = false;

        public ChildModelTask() {
            this(null, null, () -> {
            }, 0L);
        }

        public ChildModelTask(String id) {
            this(id, null, () -> {
            }, 0L);
        }

        public ChildModelTask(String id, String group) {
            this(id, group, () -> {
            }, 0L);
        }

        protected ChildModelTask(String id, long execTime) {
            this(id, null, null, execTime);
        }

        /*protected ChildModelTask(String id, String group, Long time) {
            this(id, group, null, time);
        }*/

        public ChildModelTask(String id, Runnable runnable) {
            this(id, null, runnable, 0L);
        }

        public ChildModelTask(String id, String group, Runnable runnable) {
            this(id, group, runnable, 0L);
        }

        public ChildModelTask(String id, String group, Runnable runnable, Long execTime) {
            if (StringUtil.isEmpty(id)) {
                id = toString();
            }
            if (StringUtil.isEmpty(group)) {
                group = "DEFAULT";
            }
            if (runnable == null) {
                runnable = setRunnable();
            }
            this.id = id;
            this.group = group;
            this.runnable = runnable;
            this.execTime = execTime;
        }

        public Runnable setRunnable() {
            return null;
        }

        public final void run() {
            runnable.run();
        }

        protected void setCancelTask(CancelTask cancelTask) {
            this.cancelTask = cancelTask;
        }

        public final void cancel() {
            if (cancelTask != null) {
                try {
                    cancelTask.cancel();
                    isCancel = true;
                } catch (Exception e) {
                    Log.printStackTrace(e);
                }
            }
        }

    }

    public interface CancelTask {

        void cancel();

    }

}
