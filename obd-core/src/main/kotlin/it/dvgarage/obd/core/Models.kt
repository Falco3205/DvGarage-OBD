package it.dvgarage.obd.core

enum class DtcKind(val wire: String) {
    STORED("stored"),
    PENDING("pending"),
    PERMANENT("permanent"),
}

data class Dtc(val code: String, val kind: DtcKind)

data class Snapshot(
    val vin: String?,
    val milOn: Boolean,
    val dtcs: List<Dtc>,
)

open class ObdException(message: String, cause: Throwable? = null) : Exception(message, cause)

class ObdTimeoutException(command: String) : ObdException("No reply from adapter to '$command'")

class ObdProtocolException(message: String) : ObdException(message)
