package com.surexu.sesame.util;

import org.json.JSONObject;

import java.io.File;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 玻弦红包（支付宝「玻弦打卡」小程序）token 存取。
 *
 * <p>该小程序的全部业务接口都挂在 {@code https://tools.boxiantech.com} 上，
 * 鉴权方式是请求头 <b>小写 {@code token}</b>（不是 Authorization），值由
 * {@code POST /alipay/auth} 下发的一枚 JWT（约 414 字符，payload 里没有 exp 字段）。
 *
 * <p>{@code /alipay/auth} 的入参名至今没解出来（用 authCode / code / userInfo / token
 * 全回 {@code 1001 token授权参数缺失}），所以这里换了个思路：只在小程序真正打开、
 * 由它自己发 auth 请求时，把响应里的 token 读下来存盘，之后所有接口都复用这枚 token。
 *
 * <p>本类只读响应，不修改任何请求/响应。
 */
public final class BolanToken {

    /** token 落盘文件名（放在 Sure-Xu 主目录下） */
    private static final String FILE_NAME = "bolan_token.txt";

    /** JSON 兜底匹配：token 字段值只可能是 JWT 字符集 */
    private static final Pattern TOKEN_PATTERN =
            Pattern.compile("\"token\"\\s*:\\s*\"([A-Za-z0-9._\\-]{20,})\"");

    private BolanToken() {
    }

    public static File file() {
        return new File(FileUtil.MAIN_DIRECTORY_FILE, FILE_NAME);
    }

    public static String read() {
        try {
            String s = FileUtil.readFromFile(file());
            return s == null ? "" : s.trim();
        } catch (Throwable t) {
            return "";
        }
    }

    public static void save(String token) {
        if (token == null || token.trim().isEmpty()) {
            return;
        }
        try {
            FileUtil.write2File(token.trim(), file());
        } catch (Throwable ignored) {
        }
    }

    public static void clear() {
        try {
            File f = file();
            if (f != null && f.exists()) {
                FileUtil.deleteFile(f);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 从玻弦小程序响应里捕获 token：URL 必须命中 {@code boxiantech.com/alipay/auth}。
     *
     * @return true 表示本次真的存下了新 token
     */
    public static boolean capture(String url, String body) {
        if (url == null || body == null || body.isEmpty()) {
            return false;
        }
        String u = url.toLowerCase();
        if (!u.contains("boxiantech.com") || !u.contains("alipay/auth")) {
            return false;
        }
        String token = "";
        try {
            JSONObject jo = new JSONObject(body);
            token = jo.optString("token", "");
            if (token.isEmpty()) {
                JSONObject data = jo.optJSONObject("data");
                if (data != null) {
                    token = data.optString("token", "");
                }
            }
        } catch (Throwable ignored) {
        }
        if (token.isEmpty()) {
            Matcher m = TOKEN_PATTERN.matcher(body);
            if (m.find()) {
                token = m.group(1);
            }
        }
        if (token.isEmpty()) {
            return false;
        }
        save(token);
        Log.debug("玻弦token已捕获(" + token.length() + "字符)");
        return true;
    }
}
