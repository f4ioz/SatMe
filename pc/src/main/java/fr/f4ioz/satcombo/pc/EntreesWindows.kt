/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * The volume keys on Windows: a keyboard hook in PowerShell (shipped with
 * Windows, nothing to install) prints each volume or media key as the Linux
 * helper does (`k <Linux code> <1|0>`), and when asked keeps it from Windows
 * (the PC's volume no longer moves). A hook sees every keyboard: all the
 * volume keys of the PC are taken, the knob's and a keyboard's alike.
 */
object EntreesWindows {

    val estWindows: Boolean get() = System.getProperty("os.name").orEmpty().startsWith("Windows")

    /** The one « device » shown on Windows: the PC's volume keys (a knob among them). */
    val APPAREIL = EntreesLinux.Appareil("Volume Controller (touches de volume de Windows)", "windows", "0000", "0000", volume = true, lettres = false)

    /** Windows virtual keys → the Linux codes the knob's settings use. */
    internal val CODES = mapOf(0xAF to 115, 0xAE to 114, 0xAD to 113, 0xB3 to 164, 0xB0 to 163, 0xB1 to 165)

    internal fun script(capture: Boolean): String = """
Add-Type -TypeDefinition @'
using System;
using System.Diagnostics;
using System.Runtime.InteropServices;
public static class SatMeMolette {
  public delegate IntPtr Proc(int n, IntPtr w, IntPtr l);
  [DllImport("user32.dll")] static extern IntPtr SetWindowsHookEx(int id, Proc f, IntPtr mod, uint thread);
  [DllImport("user32.dll")] static extern IntPtr CallNextHookEx(IntPtr h, int n, IntPtr w, IntPtr l);
  [DllImport("user32.dll")] static extern int GetMessage(out MSG m, IntPtr h, uint a, uint b);
  [DllImport("kernel32.dll")] static extern IntPtr GetModuleHandle(string n);
  [StructLayout(LayoutKind.Sequential)] public struct MSG { public IntPtr h; public uint m; public IntPtr w; public IntPtr l; public uint t; public int x; public int y; }
  static IntPtr hook; static Proc proc; static bool prend;
  static int Code(int vk) { switch (vk) { ${CODES.entries.joinToString(" ") { "case ${it.key}: return ${it.value};" }} default: return 0; } }
  static IntPtr Recoit(int n, IntPtr w, IntPtr l) {
    if (n >= 0) {
      int c = Code(Marshal.ReadInt32(l));
      if (c != 0) {
        int m = w.ToInt32();
        Console.Out.WriteLine("k " + c + " " + ((m == 0x100 || m == 0x104) ? 1 : 0)); Console.Out.Flush();
        if (prend) return (IntPtr)1;
      }
    }
    return CallNextHookEx(hook, n, w, l);
  }
  public static void Lance(bool p) {
    prend = p; proc = Recoit;
    hook = SetWindowsHookEx(13, proc, GetModuleHandle(Process.GetCurrentProcess().MainModule.ModuleName), 0);
    if (hook == IntPtr.Zero) { Console.Out.WriteLine("err 0 crochet clavier refusé"); Console.Out.Flush(); return; }
    Console.Out.WriteLine("ok " + (p ? 1 : 0)); Console.Out.Flush();
    MSG m; while (GetMessage(out m, IntPtr.Zero, 0, 0) > 0) { }
  }
}
'@
[SatMeMolette]::Lance(${'$'}${if (capture) "true" else "false"})
"""

    /** PowerShell's command line for [script]: encoded (UTF-16LE, base64), as PowerShell takes it. */
    fun commande(capture: Boolean): List<String> = listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
        "-EncodedCommand", Base64.getEncoder().encodeToString(script(capture).toByteArray(StandardCharsets.UTF_16LE)))

    /** The hook's keys, read as the Linux helper's. */
    class Source(veut: Boolean, commande: List<String> = commande(veut)) : EntreesLinux.Source {
        private val p: Process = ProcessBuilder(commande).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        private val sortie = p.inputStream.bufferedReader()
        override val capture: Boolean

        init {
            var pris = false
            while (true) {
                val l = sortie.readLine() ?: run { p.destroy(); throw EntreesLinux.Refus("PowerShell s'est arrêté") }
                when {
                    l.startsWith("err ") -> { p.destroy(); throw EntreesLinux.Refus(l.removePrefix("err ").substringAfter(' ')) }
                    l.startsWith("ok ") -> { pris = l.trim() == "ok 1"; break }
                }
            }
            capture = pris
        }

        override fun lit(): EntreesLinux.Evenement? {
            while (true) {
                val l = sortie.readLine() ?: return null
                val m = l.trim().split(' ')
                if (m.size == 3 && m[0] == "k") return EntreesLinux.Evenement(EntreesLinux.EV_KEY, m[1].toIntOrNull() ?: continue, m[2].toIntOrNull() ?: continue)
            }
        }

        override fun close() { runCatching { p.destroy() } }
    }
}
