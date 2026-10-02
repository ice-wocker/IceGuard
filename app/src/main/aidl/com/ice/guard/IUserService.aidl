// IceGuard UserService 接口。
// 该服务由 Shizuku 以 shell(uid 2000) 或 root 身份启动，
// 因此服务内部执行的命令天然具备 ADB 级权限。
package com.ice.guard;

interface IUserService {

    // Shizuku 服务端约定的 destroy 方法（必须保留该事务号）
    void destroy() = 16777114;

    // 执行一条命令，返回 [exitCode, stdout, stderr] 三元组。
    // 命令以数组传入、不拼接字符串，避免注入。
    String[] exec(in String[] command) = 1;

    // 返回服务进程的 uid，便于确认当前确实是 shell/root 身份
    int getServiceUid() = 2;
}
