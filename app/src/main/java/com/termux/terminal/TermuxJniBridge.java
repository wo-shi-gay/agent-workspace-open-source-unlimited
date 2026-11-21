package com.termux.terminal;

/**
 * Termux 原版 {@code JNI.java}（依赖 libtermux.so）的替身。
 *
 * 背景：本项目没有 libtermux.so，pty 由
 * {@code com.ai.assistance.operit.terminal.Pty}（libpty.so）创建，
 * 而且 pty 的读、写、尺寸、生命周期**全部由 TerminalService 托管**
 * （同一个进程里还有 AnsiScreen 镜像与 AI 命令通道在共用这条流）。
 *
 * 所以这里不再触碰任何 native 库，只保留 Termux 内核期望的调用签名：
 *   createSubprocess  → 返回 -1（会话由 attachExternalFd 挂载，不在这里建进程）
 *   setPtyWindowSize  → 空操作（尺寸经 TerminalViewClient.onEmulatorSet 转给 TerminalService.resize）
 *   waitFor           → 立即回 0（子进程由 TerminalService 收尸，本类不参与生命周期）
 *   close             → 空操作（fd 由 TerminalService 关闭，避免双重 close 误伤复用号）
 *
 * 这样既不需要 externalNativeBuild，也不会在类加载时触发
 * UnsatisfiedLinkError 把终端视图直接打崩。
 */
final class TermuxJniBridge {

    private TermuxJniBridge() {}

    static int createSubprocess(String cmd, String cwd, String[] args, String[] envVars,
                                int[] processId, int rows, int columns, int cellWidth, int cellHeight) {
        if (processId != null && processId.length > 0) processId[0] = -1;
        return -1;
    }

    static void setPtyWindowSize(int fd, int rows, int cols, int cellWidth, int cellHeight) {
        // no-op：尺寸由 TerminalActivity -> TerminalService.resize() 处理
    }

    static int waitFor(int processId) {
        return 0;
    }

    static void close(int fileDescriptor) {
        // no-op：fd 生命周期归 TerminalService
    }
}
