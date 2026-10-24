/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import java.io.File

/**
 * « Redémarrer SatMe-PC », only where SatMe-PC can start itself again
 * safely: started without a terminal (the desktop icon on Windows, the
 * Linux launcher `lance-satme.sh`), the same program and arguments known.
 *
 * Not offered under systemd (it would stop the new one with the old:
 * `systemctl --user restart satme-pc` instead), nor from a terminal
 * (the new one would run where Ctrl+C no longer reaches it).
 *
 * The new process is told the old one's number (`--apres`) and waits for
 * it to be gone before it opens anything; the old one stops as with
 * Ctrl+C (every transmitter, the rig's PTT, the rotor, the files closed).
 */
class RedemarragePc(private val args: Array<String>,
                    private val env: (String) -> String? = System::getenv,
                    // Java 22 and later give a Console even without a terminal: its isTerminal() then says.
                    private val console: () -> Boolean = {
                        System.console()?.let { c -> runCatching { c.javaClass.getMethod("isTerminal").invoke(c) as Boolean }.getOrDefault(true) } ?: false
                    },
                    private val processus: ProcessHandle = ProcessHandle.current(),
                    private val javaHome: String = System.getProperty("java.home").orEmpty(),
                    private val classpath: String = System.getProperty("java.class.path").orEmpty(),
                    private val optionsJvm: () -> List<String> = { java.lang.management.ManagementFactory.getRuntimeMXBean().inputArguments }) {

    /** Why SatMe-PC cannot start itself again here, or null when it can. */
    fun refus(): String? {
        if (!env("INVOCATION_ID").isNullOrBlank() || !env("JOURNAL_STREAM").isNullOrBlank())
            return fr.f4ioz.satcombo.i18n.I18n.t("pcd_redem_systemd")
        if (console()) return fr.f4ioz.satcombo.i18n.I18n.t("pcd_redem_terminal")
        if (commande() == null) return fr.f4ioz.satcombo.i18n.I18n.t("pcd_redem_inconnu")
        return null
    }

    /**
     * The command that starts this same SatMe-PC: its own program (the
     * Windows launcher SatMe-PC.exe) with the same arguments, or Java with
     * the same options, classpath and main class. Null when unknown.
     */
    fun commande(): List<String>? {
        val prog = processus.info().command().orElse(null) ?: return null
        val nom = File(prog).name.lowercase()
        val suite = args.toList().filterIndexed { i, a -> a != "--apres" && args.getOrNull(i - 1) != "--apres" } + listOf("--apres", processus.pid().toString())
        return if (nom == "java" || nom == "java.exe" || nom == "javaw.exe") {
            if (classpath.isBlank()) return null
            val java = File(File(javaHome, "bin"), if (nom == "java") "java" else nom).takeIf { it.canExecute() }?.path ?: prog
            listOf(java) + optionsJvm().filter { !it.startsWith("-agentlib:jdwp") } + listOf("-cp", classpath, MAIN) + suite
        } else if (File(prog).canExecute()) listOf(prog) + suite else null
    }

    /** Starts the new SatMe-PC, then this one stops (the shutdown hooks as with Ctrl+C). */
    fun lance(arret: () -> Unit = { kotlin.system.exitProcess(0) }) {
        val c = commande() ?: error(refus() ?: "redémarrage impossible")
        ProcessBuilder(c).inheritIO().start()
        arret()
    }

    companion object {
        const val MAIN = "fr.f4ioz.satcombo.pc.MainKt"

        /** At start with `--apres`: the previous SatMe-PC gone first (a minute at most), its port and files free. */
        fun attend(pid: Long, maxMs: Long = 60_000L) {
            val p = ProcessHandle.of(pid).orElse(null) ?: return
            runCatching { p.onExit().get(maxMs, java.util.concurrent.TimeUnit.MILLISECONDS) }
        }
    }
}
