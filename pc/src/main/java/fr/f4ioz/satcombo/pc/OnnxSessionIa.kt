/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer

/**
 * One ONNX model on the processor ([IaSstvPc]): a tile of planar RGB
 * (`[rgb, w, h]`) in, the same back. Loaded only through [IaSstvPc]'s own
 * class loader, with ONNX Runtime's jar downloaded beside SatMe (never on
 * SatMe's class path: compiled against it, not shipped).
 */
class OnnxSessionIa(modele: String, fils: Int) : java.util.function.Function<Array<Any>, FloatArray>, AutoCloseable {

    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession = OrtSession.SessionOptions().use { o ->
        o.setIntraOpNumThreads(fils)
        o.setInterOpNumThreads(1)
        o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        env.createSession(modele, o)
    }
    private val entree = session.inputNames.first()

    override fun apply(a: Array<Any>): FloatArray {
        val rgb = a[0] as FloatArray; val w = a[1] as Int; val h = a[2] as Int
        OnnxTensor.createTensor(env, FloatBuffer.wrap(rgb), longArrayOf(1, 3, h.toLong(), w.toLong())).use { t ->
            session.run(mapOf(entree to t)).use { r ->
                val fb = (r.get(0) as OnnxTensor).floatBuffer
                val o = FloatArray(fb.remaining()); fb.get(o)
                return o
            }
        }
    }

    override fun close() = session.close()
}
