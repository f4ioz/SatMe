package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.cat.Thd72Lien
import fr.f4ioz.satcombo.data.SettingsStore
import fr.f4ioz.satcombo.i18n.t
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The TH-D72 panel, out of the view model: each band's frequency and power,
 * the PTT band, the transmit band chosen. Every change is the operator's
 * own tap on the panel; reading never changes the rig.
 */
class Thd72Panneau(
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
    /** The TH-D72's link while it is connected. */
    private val lien: () -> Thd72Lien?,
    private val estThd72: () -> Boolean,
    /** The bands' roles changed (transmit band): the pair set up again (view model). */
    private val rolesChanges: suspend () -> Unit
) {
    /** What the TH-D72 panel shows (not in `UiState`): each band's frequency and power, PTT band. */
    data class Etat(
        val hz: List<Long?> = listOf(null, null),
        val puissance: List<Int?> = listOf(null, null),
        val bandePtt: Int? = null,
        val message: String = "",
    )
    val etat = kotlinx.coroutines.flow.MutableStateFlow(Etat())

    fun bandeTx(): Int = settings.thd72BandeTx

    /** Transmit band: saved, applied at once when connected (roles swap, PTT follows). */
    fun setBandeTx(b: Int) {
        settings.thd72BandeTx = b
        if (!estThd72()) return
        scope.launch { rolesChanges() }
    }

    fun lire() {
        val lien = lien() ?: return
        scope.launch {
            val hz = (0..1).map { b -> lien.etatBande(b)?.let { fr.f4ioz.satcombo.cat.Thd72.frequence(it) } }
            val p = (0..1).map { b -> lien.puissance(b) }
            etat.value = Etat(hz, p, lien.bandeCourante())
        }
    }

    /** Sets band [b]'s frequency (put on its step); refused ones are said. */
    fun frequence(b: Int, hz: Long) {
        val lien = lien() ?: return
        scope.launch {
            val ok = fr.f4ioz.satcombo.cat.Thd72Bande(lien, b).setFrequency(hz)
            lire()
            if (!ok) etat.value = etat.value.copy(message = t("thd72_refuse"))
        }
    }

    /** One step up or down on band [b]. */
    fun pas(b: Int, sens: Int) {
        val lien = lien() ?: return
        scope.launch {
            val c = lien.etatBande(b) ?: return@launch
            frequence(b, fr.f4ioz.satcombo.cat.Thd72.frequence(c) + sens * fr.f4ioz.satcombo.cat.Thd72.pas(c))
        }
    }

    fun puissance(b: Int, p: Int) {
        val lien = lien() ?: return
        scope.launch { lien.reglePuissance(b, p); lire() }
    }
}
