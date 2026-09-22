package au.com.naeco.voicedj

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The service runs independently of any activity, so state goes through here
 * rather than through a binder. The UI just observes.
 */
object Bus {

    enum class Phase { IDLE, WAITING_FOR_WAKE, CAPTURING, WORKING }

    private val _phase = MutableStateFlow(Phase.IDLE)
    val phase: StateFlow<Phase> = _phase

    private val _heard = MutableStateFlow("")
    val heard: StateFlow<String> = _heard

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status

    private val _serviceRunning = MutableStateFlow(false)
    val serviceRunning: StateFlow<Boolean> = _serviceRunning

    /** Long answers are spoken in part and shown in full here. */
    private val _listing = MutableStateFlow("")
    val listing: StateFlow<String> = _listing

    fun setPhase(p: Phase) { _phase.value = p }
    fun setHeard(s: String) { _heard.value = s }
    fun setStatus(s: String) { _status.value = s }
    fun setRunning(b: Boolean) { _serviceRunning.value = b }
    fun setListing(s: String) { _listing.value = s }
}
