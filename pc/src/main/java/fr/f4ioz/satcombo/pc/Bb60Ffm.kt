/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.sdr.Bb60Absent
import fr.f4ioz.satcombo.sdr.Bb60Erreur
import fr.f4ioz.satcombo.sdr.Bb60Natif
import java.io.File
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle

/**
 * Signal Hound's BB60 library (`bb_api.dll`, `libbb_api.so`), installed by
 * the user from Signal Hound's SDK — never shipped with SatMe — called
 * through Java's foreign function API (`java.lang.foreign`, Java 21 and up:
 * only what Java 21 and 22+ both have).
 *
 * Every call goes through one lock: the API is not thread-safe.
 */
class Bb60Ffm private constructor(private val lib: SymbolLookup, val chemin: String) : Bb60Natif {
    private val linker = Linker.nativeLinker()
    private val arena = Arena.ofAuto()
    private val I = ValueLayout.JAVA_INT
    private val A = ValueLayout.ADDRESS
    private val D = ValueLayout.JAVA_DOUBLE

    private fun f(nom: String, retour: java.lang.foreign.MemoryLayout, vararg args: java.lang.foreign.MemoryLayout): MethodHandle {
        val s = lib.find(nom).orElseThrow { Bb60Absent("bibliothèque Signal Hound trop ancienne : $nom absent ($chemin) — installer le SDK récent") }
        return linker.downcallHandle(s, FunctionDescriptor.of(retour, *args))
    }

    private val hVersion = f("bbGetAPIVersion", A)
    private val hTexte = f("bbGetErrorString", A, I)
    private val hListe = f("bbGetSerialNumberList2", I, A, A, A)
    private val hOuvre = f("bbOpenDevice", I, A)
    private val hOuvreSerie = f("bbOpenDeviceBySerialNumber", I, A, I)
    private val hFerme = f("bbCloseDevice", I, I)
    private val hType = f("bbGetDeviceType", I, I, A)
    private val hSerie = f("bbGetSerialNumber", I, I, A)
    private val hFirmware = f("bbGetFirmwareVersion", I, I, A)
    private val hDiag = f("bbGetDeviceDiagnostics", I, I, A, A, A)
    private val hReference = f("bbConfigureRefLevel", I, I, D)
    private val hGainAtten = f("bbConfigureGainAtten", I, I, I, I)
    private val hCentre = f("bbConfigureIQCenter", I, I, D)
    private val hIq = f("bbConfigureIQ", I, I, I, D)
    private val hType16 = f("bbConfigureIQDataType", I, I, I)
    private val hInitie = f("bbInitiate", I, I, I, I)
    private val hAbort = f("bbAbort", I, I)
    private val hParam = f("bbQueryIQParameters", I, I, A, A)
    private val hLis = f("bbGetIQUnpacked", I, I, A, I, A, I, I, A, A, A, A)

    private fun appel(h: MethodHandle, vararg a: Any): Any? = h.invokeWithArguments(*a)
    private fun statut(h: MethodHandle, vararg a: Any): Int = appel(h, *a) as Int
    private fun verifie(s: Int) { if (s < 0) throw Bb60Erreur(s, texteStatut(s)) }

    /** A C string the library keeps (never freed here), read byte by byte up to its end. */
    private fun chaine(p: MemorySegment): String {
        if (p.address() == 0L) return ""
        val s = p.reinterpret(512)
        val b = java.io.ByteArrayOutputStream()
        for (k in 0L until 512L) { val c = s.get(ValueLayout.JAVA_BYTE, k); if (c.toInt() == 0) break; b.write(c.toInt()) }
        return b.toString(Charsets.US_ASCII)
    }

    @Synchronized override fun versionApi(): String = chaine(appel(hVersion) as MemorySegment)
    @Synchronized override fun texteStatut(statut: Int): String = chaine(appel(hTexte, statut) as MemorySegment)

    @Synchronized override fun appareils(): List<Pair<Int, Int>> = Arena.ofConfined().use { a ->
        val series = a.allocate(4L * MAX_APPAREILS, 4); val types = a.allocate(4L * MAX_APPAREILS, 4); val n = a.allocate(I)
        verifie(statut(hListe, series, types, n))
        (0 until n.get(I, 0).coerceIn(0, MAX_APPAREILS)).map { series.getAtIndex(I, it.toLong()) to types.getAtIndex(I, it.toLong()) }
    }

    @Synchronized override fun ouvre(serie: Int): Int = Arena.ofConfined().use { a ->
        val h = a.allocate(I)
        verifie(if (serie == 0) statut(hOuvre, h) else statut(hOuvreSerie, h, serie))
        h.get(I, 0)
    }

    @Synchronized override fun ferme(h: Int): Int = statut(hFerme, h)

    private fun litInt(m: MethodHandle, h: Int): Int = Arena.ofConfined().use { a ->
        val v = a.allocate(I); verifie(statut(m, h, v)); v.get(I, 0)
    }
    @Synchronized override fun typeAppareil(h: Int): Int = litInt(hType, h)
    @Synchronized override fun serie(h: Int): Long = litInt(hSerie, h).toLong() and 0xFFFF_FFFFL
    @Synchronized override fun firmware(h: Int): Int = litInt(hFirmware, h)

    @Synchronized override fun diagnostics(h: Int): FloatArray = Arena.ofConfined().use { a ->
        val F = ValueLayout.JAVA_FLOAT
        val t = a.allocate(F); val v = a.allocate(F); val c = a.allocate(F)
        verifie(statut(hDiag, h, t, v, c))
        floatArrayOf(t.get(F, 0), v.get(F, 0), c.get(F, 0))
    }

    @Synchronized override fun niveauReference(h: Int, dBm: Double): Int = statut(hReference, h, dBm)
    @Synchronized override fun gainAttenuation(h: Int, gain: Int, attenuation: Int): Int = statut(hGainAtten, h, gain, attenuation)
    @Synchronized override fun centreIq(h: Int, hz: Double): Int = statut(hCentre, h, hz)
    @Synchronized override fun configureIq(h: Int, decimation: Int, bandeHz: Double): Int = statut(hIq, h, decimation, bandeHz)
    @Synchronized override fun iq16(h: Int): Int = statut(hType16, h, DATA_16SC)
    @Synchronized override fun demarreIq(h: Int): Int = statut(hInitie, h, BB_STREAMING, BB_STREAM_IQ)
    @Synchronized override fun arrete(h: Int): Int = statut(hAbort, h)

    @Synchronized override fun parametresIq(h: Int): DoubleArray = Arena.ofConfined().use { a ->
        val r = a.allocate(D); val b = a.allocate(D)
        verifie(statut(hParam, h, r, b))
        doubleArrayOf(r.get(D, 0), b.get(D, 0))
    }

    /** The samples' buffer and the out-values, kept from read to read (the reading thread only). */
    private var tampon: MemorySegment = MemorySegment.NULL
    private var taille = 0L
    private val sorties = arena.allocate(16, 4)

    @Synchronized override fun lisIq(h: Int, dest: ShortArray, paires: Int, lecture: Bb60Natif.Lecture): Int {
        val octets = paires * 4L
        if (taille < octets) { tampon = arena.allocate(octets, 8); taille = octets }
        val s = statut(hLis, h, tampon, paires, MemorySegment.NULL, 0, 0,
            sorties.asSlice(0, 4), sorties.asSlice(4, 4), sorties.asSlice(8, 4), sorties.asSlice(12, 4))
        if (s >= 0) {
            MemorySegment.copy(tampon, ValueLayout.JAVA_SHORT, 0L, dest, 0, 2 * paires)
            lecture.restants = sorties.get(I, 0)
            lecture.perte = sorties.get(I, 4) != 0
            lecture.secondes = sorties.get(I, 8)
            lecture.nanos = sorties.get(I, 12)
        }
        return s
    }

    companion object {
        private const val MAX_APPAREILS = 8
        private const val DATA_16SC = 1
        private const val BB_STREAMING = 4
        private const val BB_STREAM_IQ = 0
        const val ABSENTE = "bibliothèque Signal Hound absente — installer le SDK"

        private val os = System.getProperty("os.name").orEmpty().lowercase()
        val windows = os.startsWith("windows")
        private val mac = os.contains("mac")

        /** The library's file names here, and the FTDI library it needs beside it. */
        val noms: List<String> get() = if (windows) listOf("bb_api.dll") else if (mac) listOf("libbb_api.dylib") else listOf("libbb_api.so", "libbb_api.so.5")
        private val ftdi: String get() = if (windows) "ftd2xx.dll" else if (mac) "libftd2xx.dylib" else "libftd2xx.so"
        /** Where the SDK's README puts it on Linux (`/usr/local/lib`), and the usual others. */
        private val dossiers: List<String> get() = if (windows) emptyList() else listOf("/usr/local/lib", "/usr/lib", "/usr/lib64", "/usr/lib/x86_64-linux-gnu", "/usr/lib/aarch64-linux-gnu")

        private val charges = HashMap<String, Bb60Ffm>()

        /**
         * The library from [chemin] (the file, or its folder; empty: the
         * system's library path, then the usual folders), loaded once.
         * [Bb60Absent] when it cannot be: not installed, too old, Java too old.
         */
        @Synchronized fun charge(chemin: String = ""): Bb60Natif {
            charges[chemin]?.let { return it }
            val java = Runtime.version().feature()
            if (java < 21) throw Bb60Absent("le BB60 demande Java 21 ou plus (Java $java ici)")
            val essais = ArrayList<String>()
            val c = chemin.trim()
            if (c.isNotEmpty()) {
                val f = File(c)
                if (f.isDirectory) noms.forEach { essais += File(f, it).path } else essais += f.path
            } else {
                essais += noms
                for (d in dossiers) for (n in noms) File(d, n).takeIf { it.isFile }?.let { essais += it.path }
            }
            var raison = ""
            for (e in essais) {
                try {
                    val lib = ouvreBibliotheque(e)
                    return Bb60Ffm(lib, e).also { charges[chemin] = it }
                } catch (x: Bb60Absent) {
                    throw x
                } catch (x: IllegalCallerException) {
                    throw Bb60Absent("accès natif refusé par Java : lancer SatMe avec --enable-native-access=ALL-UNNAMED")
                } catch (x: IllegalArgumentException) {
                    if (raison.isEmpty() || File(e).isFile) raison = x.message.orEmpty()
                } catch (x: LinkageError) {
                    throw Bb60Absent("le BB60 demande Java 21 ou plus (" + (x.message ?: x.javaClass.simpleName) + ")")
                }
            }
            throw Bb60Absent(ABSENTE + (if (c.isNotEmpty()) " (rien d'utilisable à $c" + (if (raison.isNotEmpty()) " : $raison" else "") + ")" else " (bb_api introuvable)"))
        }

        /** [e] a bare name: the system's search; a path: that file, the FTDI library beside it loaded first. */
        private fun ouvreBibliotheque(e: String): SymbolLookup {
            val f = File(e)
            if (f.isAbsolute || e.contains(File.separatorChar)) {
                if (!f.isFile) throw IllegalArgumentException("fichier absent : $e")
                File(f.absoluteFile.parentFile, ftdi).takeIf { it.isFile }?.let { d -> runCatching { SymbolLookup.libraryLookup(d.toPath(), Arena.global()) } }
                return SymbolLookup.libraryLookup(f.toPath(), Arena.global())
            }
            return SymbolLookup.libraryLookup(e, Arena.global())
        }
    }
}
