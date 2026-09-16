// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

/**
 * IPC message definitions and data keys for communication between
 * the main IME process (:root) and the isolated voice recording service (:voice).
 */
object VoiceIpcProtocol {
    // Client -> Service Commands
    const val CMD_REGISTER_CLIENT = 1
    const val CMD_UNREGISTER_CLIENT = 2
    const val CMD_START_RECORDING = 3
    const val CMD_PAUSE_RECORDING = 4
    const val CMD_RESUME_RECORDING = 5
    const val CMD_STOP_RECORDING = 6
    const val CMD_SET_GAIN = 7
    const val CMD_FORCE_RELEASE = 8

    // Service -> Client Events
    const val EVENT_STATE_CHANGED = 101
    const val EVENT_RMS_UPDATE = 102
    const val EVENT_SPEECH_ACTIVITY = 103
    const val EVENT_ERROR = 104
    const val EVENT_TRANSCRIPTION_PREVIEW = 105
    const val EVENT_FINAL_TRANSCRIPTION = 106

    // Bundle Data Keys
    const val KEY_RMS = "key_rms"
    const val KEY_ERROR_MESSAGE = "key_error_message"
    const val KEY_TRANSCRIPTION_TEXT = "key_transcription_text"
    const val KEY_FINAL_TEXT = "key_final_text"

    // Service States
    enum class ServiceState {
        IDLE,
        RECORDING,
        PAUSED,
        ERROR
    }
}
