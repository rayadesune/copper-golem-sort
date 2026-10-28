"""把 0.4.2 源码改出一份带计时器的临时构建（只在测量期间使用）。"""
import os, re, shutil, sys

src = "/tmp/cgp-build-042/src"
dst = "/tmp/cgp-measure-src"
if os.path.exists(dst):
    shutil.rmtree(dst)
shutil.copytree(src, dst)

base = os.path.join(dst, "dev/cgp/coppergolempatch")
listener = os.path.join(base, "listener/TargetValidationListener.java")
gossip = os.path.join(base, "gossip/GossipTask.java")
plugin = os.path.join(base, "CopperGolemPatchPlugin.java")

metrics = '''package dev.cgp.coppergolempatch;

/** 临时测量用：只在 measure 构建里存在。 */
public final class Metrics {
    public static long validateCalls, validateNanos;
    public static long watchdogRuns, watchdogNanos;
    public static long gossipRuns, gossipNanos;

    public static String report() {
        return String.format(
            "[METRICS 30s] validate: %d 次, 合计 %.1f ms, 单次 %.2f µs | "
          + "watchdog: %d 轮, 合计 %.1f ms, 单轮 %.1f µs | "
          + "gossip: %d 轮, 合计 %.1f ms, 单轮 %.1f µs",
            validateCalls, validateNanos / 1e6, validateCalls == 0 ? 0 : validateNanos / 1e3 / validateCalls,
            watchdogRuns, watchdogNanos / 1e6, watchdogRuns == 0 ? 0 : watchdogNanos / 1e3 / watchdogRuns,
            gossipRuns, gossipNanos / 1e6, gossipRuns == 0 ? 0 : gossipNanos / 1e3 / gossipRuns);
    }

    public static void reset() {
        validateCalls = validateNanos = 0;
        watchdogRuns = watchdogNanos = 0;
        gossipRuns = gossipNanos = 0;
    }
}
'''
open(os.path.join(base, "Metrics.java"), "w").write(metrics)


def patch(path, old, new, label):
    text = open(path).read()
    if old not in text:
        sys.exit(f"锚点没找到: {label}")
    open(path, "w").write(text.replace(old, new, 1))
    print("patched", label)


patch(listener,
      "    @EventHandler\n    public void onValidate(ItemTransportingEntityValidateTargetEvent event) {",
      "    @EventHandler\n    public void onValidate(ItemTransportingEntityValidateTargetEvent event) {\n"
      "        long __t = System.nanoTime();\n"
      "        try {\n            handleValidate(event);\n        } finally {\n"
      "            dev.cgp.coppergolempatch.Metrics.validateCalls++;\n"
      "            dev.cgp.coppergolempatch.Metrics.validateNanos += System.nanoTime() - __t;\n"
      "        }\n    }\n\n"
      "    private void handleValidate(ItemTransportingEntityValidateTargetEvent event) {",
      "listener.onValidate")

patch(listener,
      "    public void watchHoldTimeout() {",
      "    public void watchHoldTimeout() {\n"
      "        long __t = System.nanoTime();\n"
      "        try { watchHoldTimeoutInternal(); } finally {\n"
      "            dev.cgp.coppergolempatch.Metrics.watchdogRuns++;\n"
      "            dev.cgp.coppergolempatch.Metrics.watchdogNanos += System.nanoTime() - __t;\n"
      "        }\n    }\n\n"
      "    private void watchHoldTimeoutInternal() {",
      "listener.watchHoldTimeout")

patch(gossip,
      "    @Override\n    public void run() {",
      "    @Override\n    public void run() {\n"
      "        long __t = System.nanoTime();\n"
      "        try { runInternal(); } finally {\n"
      "            dev.cgp.coppergolempatch.Metrics.gossipRuns++;\n"
      "            dev.cgp.coppergolempatch.Metrics.gossipNanos += System.nanoTime() - __t;\n"
      "        }\n    }\n\n"
      "    private void runInternal() {",
      "gossip.run")

patch(plugin,
      "        scheduleGossipTask();\n\n        getLogger().info(\"Copper Golem Patch enabled.\");",
      "        scheduleGossipTask();\n\n"
      "        getServer().getScheduler().runTaskTimer(this, () -> {\n"
      "            getLogger().info(dev.cgp.coppergolempatch.Metrics.report());\n"
      "            dev.cgp.coppergolempatch.Metrics.reset();\n"
      "        }, 600L, 600L);\n\n"
      "        getLogger().info(\"Copper Golem Patch enabled.\");",
      "plugin.metricsTask")

print("OK: 临时测量源码在", dst)
