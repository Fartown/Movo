package io.github.mangi.eta.agent.runtime

internal object AgentRuntimeAdmission {
    data class Owner(val runId: String, val voice: Boolean)
    enum class Decision { START, ATTACH, BUSY }
    fun decide(request: Owner, active: Owner?, pending: Owner?): Decision = when {
        active?.runId == request.runId -> Decision.ATTACH
        pending?.runId == request.runId -> Decision.BUSY
        (active != null || pending != null) && (request.voice || active?.voice == true || pending?.voice == true) -> Decision.BUSY
        else -> Decision.START
    }
}
