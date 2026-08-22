package dev.vhos.headunit

import android.os.Handler
import android.os.Looper
import dev.vhos.model.DeviceRole
import dev.vhos.model.DeviceSnapshot
import dev.vhos.model.HeadUnitSnapshot
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

object HeadUnitRuntime {
    private val main = Handler(Looper.getMainLooper())
    private val observers = CopyOnWriteArraySet<(HeadUnitSnapshot) -> Unit>()
    private val deliveryScheduled = AtomicBoolean(false)
    @Volatile private var current = HeadUnitSnapshot()
    private val deliverLatest = Runnable {
        deliveryScheduled.set(false)
        val value = current
        observers.forEach { it(value) }
    }

    fun snapshot(): HeadUnitSnapshot = current

    fun observe(observer: (HeadUnitSnapshot) -> Unit) {
        observers += observer
        main.post { observer(current) }
    }

    fun removeObserver(observer: (HeadUnitSnapshot) -> Unit) {
        observers -= observer
    }

    fun setRunning(running: Boolean, status: String) = update {
        it.copy(running = running, status = status)
    }

    fun updateDevice(snapshot: DeviceSnapshot) = update { state ->
        when (snapshot.role) {
            DeviceRole.OBD_CAN -> state.copy(obd = snapshot, status = snapshot.detail)
            DeviceRole.AC_SENSOR -> state.copy(ac = snapshot, status = snapshot.detail)
        }
    }

    fun updateCounts(logicalFrames: Long, canObservations: Long) = update {
        it.copy(storedLogicalFrames = logicalFrames, storedCanObservations = canObservations)
    }

    fun markExport(epochMs: Long) = update { it.copy(lastExportAtEpochMs = epochMs) }
    fun markImport(epochMs: Long) = update { it.copy(lastImportAtEpochMs = epochMs) }

    private fun update(transform: (HeadUnitSnapshot) -> HeadUnitSnapshot) {
        synchronized(this) { current = transform(current) }
        if (deliveryScheduled.compareAndSet(false, true)) {
            main.postDelayed(deliverLatest, DELIVERY_INTERVAL_MILLIS)
        }
    }

    private const val DELIVERY_INTERVAL_MILLIS = 100L
}
