package com.snailmai.todoist;

/** 只携带安全错误码，避免文件内容／模型输出进入异常日志。 */
final class Failure extends RuntimeException {
    final String code;
    Failure(String code) { super(code, null, false, false); this.code = code; }
    static void require(boolean condition, String code) { if (!condition) throw new Failure(code); }
}
