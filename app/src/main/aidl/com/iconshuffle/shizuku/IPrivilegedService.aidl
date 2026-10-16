// Shizuku 用户服务的跨进程接口。
//
// 为什么用 AIDL 而不是普通 Kotlin interface：
// Shizuku.bindUserService 会把 ServiceConnection 里的 IBinder 交给宿主，
// 跨进程通信必须走 Binder 编解码，普通接口无法序列化。
//
// 约束：Binder 单次事务上限约 1MB，所以大文件不要走 byte[]，
// 一律「特权侧复制到共享暂存目录 → 宿主读文件」。

interface IPrivilegedService {

    /** 身份与关键路径探测报告（多行文本）。 */
    String probe();

    /** 列出目录内容，不存在或无权限时返回空数组。 */
    String[] listDir(String path);

    /** src 是否存在且可读。 */
    boolean canRead(String path);

    /** 取文件大小，失败返回 -1。 */
    long length(String path);

    /**
     * 以 Shizuku 身份把 src 复制到 dst。
     * @return 复制后的字节数；失败返回负数（-1 不存在 / -2 不可读 / -3 IO 异常）
     */
    long copyFile(String src, String dst);

    /** 读取文件头部若干字节（用于嗅探 SQLite 头），失败返回空数组。 */
    byte[] readFileHead(String path, int length);

    /** stat 输出，形如 "644 shell 123456 1700000000"，失败返回空串。 */
    String statFile(String path);

    /** chmod，返回是否成功。 */
    boolean chmod(String path, int mode);

    /** 以 Shizuku 身份执行 shell 命令，返回 "exitCode\nstdout\nstderr"。 */
    String exec(String command);

    /** 结束指定进程名的进程，返回 kill 是否至少命中一个。 */
    boolean killProcess(String processName);
}
