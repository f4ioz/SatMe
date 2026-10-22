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
import java.util.concurrent.TimeUnit

/**
 * The Pi itself restarted or switched off, from its SatMe-Node: the service
 * runs as `satme`, which may run these two commands as root and nothing else
 * (`/etc/sudoers.d/satme-noeud`, written by `installe-noeud.sh`).
 */
interface SystemeNoeudPc {
    /** Null when [verbe] ([REDEMARRE], [ETEINT]) may be run, else why not, in French with the fix. */
    fun permis(verbe: String): String?
    /** Runs [verbe]; null when the system took it, else why not. */
    fun lance(verbe: String): String?

    companion object {
        const val REDEMARRE = "reboot"
        const val ETEINT = "poweroff"
        const val SYSTEMCTL = "/usr/bin/systemctl"
        const val SUDO = "/usr/bin/sudo"
        const val SANS_PERMISSION = "le service satme n'a pas le droit de redémarrer ni d'éteindre le Pi : relancer l'installeur sur le node " +
            "(sudo ./satme/noeud/installe-noeud.sh), qui l'accorde dans /etc/sudoers.d/satme-noeud"

        /**
         * `sudo -n systemctl reboot|poweroff`; or, for a trial, the program
         * named by `SATME_NOEUD_COMMANDE_SYSTEME`, given the verb (never a
         * real reboot of the PC a trial runs on).
         */
        fun reel(env: (String) -> String? = System::getenv): SystemeNoeudPc {
            val essai = env("SATME_NOEUD_COMMANDE_SYSTEME")?.takeIf { it.isNotBlank() }
            return object : SystemeNoeudPc {
                override fun permis(verbe: String): String? {
                    if (essai != null) return null
                    if (!File(SUDO).canExecute()) return "sudo absent du node : " + SANS_PERMISSION
                    // Lists the command if allowed (exit 0), without ever asking a password.
                    val (code, _) = lance(listOf(SUDO, "-n", "-l", SYSTEMCTL, verbe), 5) ?: return SANS_PERMISSION
                    return if (code == 0) null else SANS_PERMISSION
                }
                override fun lance(verbe: String): String? {
                    val cmd = if (essai != null) listOf(essai, verbe) else listOf(SUDO, "-n", SYSTEMCTL, verbe)
                    val (code, sortie) = lance(cmd, 20) ?: return "la commande ne répond pas"
                    return if (code == 0) null else "refusé par le système (" + code + ") : " + sortie.trim().take(200)
                }
            }
        }

        private fun lance(cmd: List<String>, secondes: Long): Pair<Int, String>? = runCatching {
            val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
            runCatching { p.outputStream.close() }
            if (!p.waitFor(secondes, TimeUnit.SECONDS)) { p.destroyForcibly(); return null }
            p.exitValue() to p.inputStream.bufferedReader().readText()
        }.getOrElse { 127 to (it.message ?: "") }
    }
}
