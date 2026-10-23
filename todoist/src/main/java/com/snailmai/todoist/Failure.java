package com.snailmai.todoist;

import java.util.Map;

/** 只携带安全错误码，避免文件内容／模型输出进入异常日志。 */
final class Failure extends RuntimeException {
    final String code;
    final Map<String, Object> details;
    Failure(String code) { this(code, Map.of()); }
    Failure(String code, Map<String, Object> details) { super(code, null, false, false); this.code = code; this.details = details; }
    static void require(boolean condition, String code) { if (!condition) throw new Failure(code); }
}
