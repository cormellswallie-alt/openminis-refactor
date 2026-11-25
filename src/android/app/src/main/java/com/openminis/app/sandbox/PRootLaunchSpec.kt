package com.openminis.app.sandbox

import android.content.Context
import java.io.File

/**
 * Single source of truth for building PRoot argv and environment across
 * [FreshProcessShell], [StatelessWorkerShell], and [PersistentShell].
 *
 * Centralizing these flags guarantees flag-for-flag parity across all shell
 * execution strategies (bind mounts, `--link2symlink`, `--native-offload`,
 * `PROOT_LOADER*`, `TZ`, `MINIS_CHAT_SESSION_ID`, and GH#186 `PROOT_NO_SECCOMP`).
 */
internal object PRootLaunchSpec {

    fun buildArgv(
        prootBinary: File,
        rootfsDir: File,
        sessionBindMounts: Map<String, String>,
        guestArgs: List<String>,
        offloadSocketName: String = NativeOffloadServer.socketName,
        offloadHandlers: Set<String> = NativeOffloadServer.registeredHandlers,
        includeFakeNetlink: Boolean = true,
    ): List<String> = buildList(16 + sessionBindMounts.size * 2 + guestArgs.size) {
        add(prootBinary.absolutePath)
        add("-0")
        add("--link2symlink")
        add("-r"); add(rootfsDir.absolutePath)
        add("-b"); add("/dev")
        add("-b"); add("/proc")
        add("-b"); add("/sys")
        add("-w"); add("/root")
        for ((linuxPath, hostPath) in sessionBindMounts) {
            add("-b"); add("$hostPath:$linuxPath")
        }
        if (offloadHandlers.isNotEmpty()) {
            add("--native-offload=$offloadSocketName:${offloadHandlers.joinToString(",")}")
        }
        if (includeFakeNetlink) {
            add("--fake-netlink")
        }
        addAll(guestArgs)
    }

    fun buildProcessBuilder(
        context: Context,
        sessionId: String,
        sessionBindMounts: Map<String, String>,
        guestArgs: List<String>,
        extraEnv: Map<String, String> = emptyMap(),
        useNoSeccomp: Boolean = false,
        redirectErrorStream: Boolean = false,
        includeFakeNetlink: Boolean = true,
    ): ProcessBuilder {
        val rootfsManager = RootfsManager.getInstance(context)
        val cmd = buildArgv(
            prootBinary = rootfsManager.prootBinary,
            rootfsDir = rootfsManager.rootfsDir,
            sessionBindMounts = sessionBindMounts,
            guestArgs = guestArgs,
            includeFakeNetlink = includeFakeNetlink,
        )
        val pb = ProcessBuilder(cmd)
        pb.redirectErrorStream(redirectErrorStream)
        applyEnvironment(
            env = pb.environment(),
            context = context,
            sessionId = sessionId,
            extraEnv = extraEnv,
            useNoSeccomp = useNoSeccomp,
        )
        return pb
    }

    private fun applyEnvironment(
        env: MutableMap<String, String>,
        context: Context,
        sessionId: String,
        extraEnv: Map<String, String>,
        useNoSeccomp: Boolean,
    ) {
        env["PROOT_TMP_DIR"] = PRootKernel.getProotTmpDir(context).absolutePath
        if (PRootKernel.nativeLibDir.isNotEmpty()) env["LD_LIBRARY_PATH"] = PRootKernel.nativeLibDir
        if (PRootKernel.prootLoaderPath.isNotEmpty()) env["PROOT_LOADER"] = PRootKernel.prootLoaderPath
        if (PRootKernel.prootLoader32Path.isNotEmpty()) env["PROOT_LOADER_32"] = PRootKernel.prootLoader32Path
        env["TERM"] = "dumb"
        env["PS1"] = ""
        env["TZ"] = PRootKernel.posixTz()
        if (com.openminis.app.BuildConfig.DEV_TOOLS) env["MINIS_NOFF_DEBUG"] = "1"
        env["MINIS_CHAT_SESSION_ID"] = sessionId
        for ((k, v) in PRootKernel.customEnvironment) env[k] = v
        for ((k, v) in extraEnv) env[k] = v
        if (useNoSeccomp) {
            env[SeccompFallbackPolicy.NO_SECCOMP_ENV] = SeccompFallbackPolicy.NO_SECCOMP_VALUE
        }
    }
}
