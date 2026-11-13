package com.surexu.sesame.util;

import android.content.Context;

import com.fasterxml.jackson.databind.JsonMappingException;

import java.io.File;
import java.util.Calendar;

import com.surexu.sesame.R;
import androidx.annotation.Keep;
import lombok.Data;

@Data
public class Statistics {
    
    private static final String TAG = Statistics.class.getSimpleName();
    
    public static final Statistics INSTANCE = new Statistics();
    
    private TimeStatistics year = new TimeStatistics();
    private TimeStatistics month = new TimeStatistics();
    private TimeStatistics day = new TimeStatistics();
    private TimeStatistics all = new TimeStatistics();

    /** 本轮（一次执行周期）收取的能量。仅内存计数、不落盘（静态字段不进 Jackson） */
    private static volatile int roundCollected = 0;

    /** 本轮收取能量 */
    public static int getRoundCollected() {
        return roundCollected;
    }

    /** 新一轮开始时清零本轮收取计数 */
    public static void resetRoundCollected() {
        roundCollected = 0;
    }

    /**
     * 用服务端权威值**覆盖**今日收取能量（而非累加）。
     * <p>逐点 {@link #addData} 只要漏掉一个入口，当日账目就永久偏小，故改由服务端明细直接定值。
     * <p>month/year/all 按差值同步追加：月、年、累计都是「历史累计维度」，
     * 被覆盖的今日值此前已计入它们，换天/换月/换年清理时它们不会被清零。
     *
     * @param todayCollected 服务端给出的今日收取能量；传负数表示无效值、忽略
     * @return 本次覆盖造成的日维度增量（可能为负）
     */
    public static synchronized int setDayCollectedFromServer(int todayCollected) {
        if (todayCollected < 0) {
            return 0;
        }
        int delta = todayCollected - INSTANCE.day.collected;
        if (delta == 0) {
            return 0;
        }
        INSTANCE.day.collected = todayCollected;
        INSTANCE.month.collected += delta;
        INSTANCE.year.collected += delta;
        // 累计是所有日子的总和，只做加法、永不清零；这里必须跟着补差值，
        // 否则「今日 3 千多、累计 1 千多」就是漏加造成的
        INSTANCE.all.collected += delta;
        return delta;
    }

    /**
     * synchronized：与 save/load/unload 共用 Statistics.class 监视器。
     * <p>各个模块跑在各自线程上、都会累加同一个计数器，原先的 += 既会丢更新，
     * 也可能正好撞上 save() 的序列化过程。
     */
    public static synchronized void addData(DataType dt, int i) {
        Statistics stat = INSTANCE;
        switch (dt) {
            case COLLECTED:
                stat.day.collected += i;
                stat.month.collected += i;
                stat.year.collected += i;
                stat.all.collected += i;
                roundCollected += i;
                break;
            case HELPED:
                stat.day.helped += i;
                stat.month.helped += i;
                stat.year.helped += i;
                stat.all.helped += i;
                break;
            case WATERED:
                stat.day.watered += i;
                stat.month.watered += i;
                stat.year.watered += i;
                stat.all.watered += i;
                break;
            case WATEREDCOUNT:
                stat.day.wateredcount += i;
                stat.month.wateredcount += i;
                stat.year.wateredcount += i;
                stat.all.wateredcount += i;
                break;
            case WATERINGCOUNT:
                stat.day.wateringcount += i;
                stat.month.wateringcount += i;
                stat.year.wateringcount += i;
                stat.all.wateringcount += i;
                break;
        }
    }
    
    public static synchronized int getData(TimeType tt, DataType dt) {
        Statistics stat = INSTANCE;
        int data = 0;
        TimeStatistics ts = null;
        switch (tt) {
            case YEAR:
                ts = stat.year;
                break;
            case MONTH:
                ts = stat.month;
                break;
            case DAY:
                ts = stat.day;
                break;
            case ALL:
                ts = stat.all;
                break;
        }
        if (ts != null) {
            // 累计就是账本里那一栏，老老实实读出来；不再靠「取四档最大值」临时糊弄界面。
            // 老账本缺的差额在加载时一次性补回（见 repairAllLedgerOnce），之后只靠逐笔累加。
            switch (dt) {
                case TIME:
                    data = ts.time;
                    break;
                case COLLECTED:
                    data = ts.collected;
                    break;
                case HELPED:
                    data = ts.helped;
                    break;
                case WATERED:
                    data = ts.watered;
                    break;
                case WATEREDCOUNT:
                    data = ts.wateredcount;
                    break;
                case WATERINGCOUNT:
                    data = ts.wateringcount;
                    break;
            }
        }
        return data;
    }
    
    public static String getText() {
        
        StringBuilder table = new StringBuilder();
        // 添加表头
        table.append("今年  收: ").append(getData(TimeType.YEAR, DataType.COLLECTED)).append(" 帮: ").append(getData(TimeType.YEAR, DataType.HELPED)).append(" 浇: ").append(getData(TimeType.YEAR, DataType.WATERED));
        table.append("\n今月  收: ").append(getData(TimeType.MONTH, DataType.COLLECTED)).append(" 帮: ").append(getData(TimeType.MONTH, DataType.HELPED)).append(" 浇: ").append(getData(TimeType.MONTH, DataType.WATERED));
        table.append("\n今日  收: ").append(getData(TimeType.DAY, DataType.COLLECTED)).append(" 帮: ").append(getData(TimeType.DAY, DataType.HELPED)).append(" 浇: ").append(getData(TimeType.DAY, DataType.WATERED));
        table.append("\n被水次数  日: ").append(getData(TimeType.DAY, DataType.WATEREDCOUNT)).append(" 月: ").append(getData(TimeType.MONTH, DataType.WATEREDCOUNT)).append(" 年: ").append(getData(TimeType.YEAR, DataType.WATEREDCOUNT));
        table.append("\n浇水次数  日: ").append(getData(TimeType.DAY, DataType.WATERINGCOUNT)).append(" 月: ").append(getData(TimeType.MONTH, DataType.WATERINGCOUNT)).append(" 年: ").append(getData(TimeType.YEAR, DataType.WATERINGCOUNT));
        return table.toString();
    }
    
    public static String getText(Context context) {
        return getText(context.getString(R.string.year), context.getString(R.string.month), context.getString(R.string.day), context.getString(R.string.collected), context.getString(R.string.helped), context.getString(R.string.watered), context.getString(R.string.wateredcount),
                context.getString(R.string.wateringcount));
    }
    
    public static String getText(String year, String month, String day, String collected, String helped, String watered, String wateredcount, String wateringcount) {
        return year + "  " + collected + ": " + getData(TimeType.YEAR, DataType.COLLECTED) + " " + helped + ": " + getData(TimeType.YEAR, DataType.HELPED) + " " + watered + ": " + getData(TimeType.YEAR, DataType.WATERED) +
               "\n" + month + "  " + collected + ": " + getData(TimeType.MONTH, DataType.COLLECTED) + " " + helped + ": " + getData(TimeType.MONTH, DataType.HELPED) + " " + watered + ": " + getData(TimeType.MONTH, DataType.WATERED) +
               "\n" + day + "  " + collected + ": " + getData(TimeType.DAY, DataType.COLLECTED) + " " + helped + ": " + getData(TimeType.DAY, DataType.HELPED) + " " + watered + ": " + getData(TimeType.DAY, DataType.WATERED) +
               "\n" + wateredcount + "  " + "日" + ": " + getData(TimeType.DAY, DataType.WATEREDCOUNT) + "; " + "月" + ": " + getData(TimeType.MONTH, DataType.WATEREDCOUNT) + ";  " + "年" + ": " + getData(TimeType.YEAR, DataType.WATEREDCOUNT) +
               "\n" + wateringcount + "  " + "日" + ": " + getData(TimeType.DAY, DataType.WATERINGCOUNT) + ";  " + "月" + ": " + getData(TimeType.MONTH, DataType.WATERINGCOUNT) + ";  " + "年" + ": " + getData(TimeType.YEAR, DataType.WATERINGCOUNT);
    }
    
    public static synchronized Statistics load() {
        try {
            File statisticsFile = FileUtil.getStatisticsFile();
            if (statisticsFile.exists()) {
                String json = FileUtil.readFromFile(statisticsFile);
                JsonUtil.copyMapper().readerForUpdating(INSTANCE).readValue(json);
                String formatted = JsonUtil.toFormatJsonString(INSTANCE);
                if (formatted != null && !formatted.equals(json)) {
                    // 回写只允许改格式、不许改数据
                    if (JsonUtil.isRewriteLossless(json, formatted)) {
                        Log.i(TAG, "重新格式化 statistics.json");
                        FileUtil.write2File(formatted, statisticsFile);
                    } else {
                        Log.i(TAG, "statistics.json 加载结果与磁盘数据不一致，已跳过重新格式化");
                    }
                }
            }
            else {
                JsonUtil.copyMapper().updateValue(INSTANCE, new Statistics());
                Log.i(TAG, "初始化 statistics.json");
                FileUtil.write2File(JsonUtil.toFormatJsonString(INSTANCE), statisticsFile);
            }
        }
        catch (Throwable t) {
            Log.printStackTrace(TAG, t);
            Log.i(TAG, "统计文件格式有误，已重置统计文件");
            try {
                JsonUtil.copyMapper().updateValue(INSTANCE, new Statistics());
                FileUtil.write2File(JsonUtil.toFormatJsonString(INSTANCE), FileUtil.getStatisticsFile());
            }
            catch (JsonMappingException e) {
                Log.printStackTrace(TAG, e);
            }
        }
        // 放在格式化回写判断之后：修旧账只改内存，不干扰「文件是否只是格式不同」的判断
        repairAllLedgerOnce();
        return INSTANCE;
    }
    
    public static synchronized void unload() {
        try {
            JsonUtil.copyMapper().updateValue(INSTANCE, new Statistics());
        }
        catch (JsonMappingException e) {
            Log.printStackTrace(TAG, e);
        }
    }

    /**
     * 界面进程专用：只读地把「当前账号」的统计文件读进内存，不回写磁盘。
     * <p>原来界面也走 {@link #load()}，既会跟注入进程抢着写同一个文件，又只在首次读一次，
     * 导致日志里数字涨了、首页还是老数字；切号后更是把两个号的数据混在一起。
     * 每次先清空再读，界面永远等于磁盘上当前账号的最新快照。
     */
    public static synchronized void loadForUi() {
        unload();
        try {
            File f = FileUtil.getStatisticsFile();
            if (f.isFile()) {
                String json = FileUtil.readFromFile(f);
                if (json != null && !json.isEmpty()) {
                    JsonUtil.copyMapper().readerForUpdating(INSTANCE).readValue(json);
                }
            }
        } catch (Throwable t) {
            Log.printStackTrace(TAG, t);
        }
        // 界面只读，不回写磁盘；但旧账本也要按同一套修一下，避免界面短暂显示偏小的累计
        repairAllLedgerOnce();
    }
    
    public static synchronized void save() {
        save(Calendar.getInstance());
    }
    
    public static synchronized void save(Calendar nowCalendar) {
        if (updateDay(nowCalendar)) {
            Log.system(TAG, "重置 statistics.json");
        }
        else {
            // 每次落盘都记一行会淹没有效日志（实测约 75 行/天），降为由「抓包记录」开关控制的调试日志
            Log.debug(TAG + ", 保存 statistics.json");
        }
        FileUtil.write2File(JsonUtil.toFormatJsonString(INSTANCE), FileUtil.getStatisticsFile());
    }

    /**
     * 只修一次旧账：老版本校准今日明细时漏给累计加差值，导致 all 比今日还小。
     * 加载时把这段差额补回去，之后累计完全靠逐笔加法，日/月/年换期清理时绝不碰它。
     * <p>这不是「显示时取最大值」——是直接把账本改正，界面读到多少就是多少。
     */
    private static void repairAllLedgerOnce() {
        TimeStatistics a = INSTANCE.all;
        a.collected = maxOf(a.collected, INSTANCE.year.collected, INSTANCE.month.collected, INSTANCE.day.collected);
        a.helped = maxOf(a.helped, INSTANCE.year.helped, INSTANCE.month.helped, INSTANCE.day.helped);
        a.watered = maxOf(a.watered, INSTANCE.year.watered, INSTANCE.month.watered, INSTANCE.day.watered);
        a.wateredcount = maxOf(a.wateredcount, INSTANCE.year.wateredcount,
                INSTANCE.month.wateredcount, INSTANCE.day.wateredcount);
        a.wateringcount = maxOf(a.wateringcount, INSTANCE.year.wateringcount,
                INSTANCE.month.wateringcount, INSTANCE.day.wateringcount);
    }

    private static int maxOf(int... values) {
        int max = values[0];
        for (int i = 1; i < values.length; i++) {
            if (values[i] > max) {
                max = values[i];
            }
        }
        return max;
    }
    
    public static Boolean updateDay(Calendar nowCalendar) {
        int ye = nowCalendar.get(Calendar.YEAR);
        int mo = nowCalendar.get(Calendar.MONTH) + 1;
        int da = nowCalendar.get(Calendar.DAY_OF_MONTH);
        if (ye != INSTANCE.year.time) {
            INSTANCE.year.reset(ye);
            INSTANCE.month.reset(mo);
            INSTANCE.day.reset(da);
        }
        else if (mo != INSTANCE.month.time) {
            INSTANCE.month.reset(mo);
            INSTANCE.day.reset(da);
        }
        else if (da != INSTANCE.day.time) {
            INSTANCE.day.reset(da);
        }
        else {
            return false;
        }
        return true;
    }
    
    public enum TimeType {
        YEAR, MONTH, DAY, ALL
    }
    
    public enum DataType {
        TIME, COLLECTED, HELPED, WATERED, WATEREDCOUNT, WATERINGCOUNT
    }
    
    @Data
    @Keep
    public static class TimeStatistics {
        int time;
        int collected, helped, watered, wateredcount, wateringcount;
        
        public TimeStatistics() {
        }
        
        TimeStatistics(int i) {
            reset(i);
        }
        
        public void reset(int i) {
            time = i;
            collected = 0;
            helped = 0;
            watered = 0;
            wateredcount = 0;
            wateringcount = 0;
        }
    }
    
}
