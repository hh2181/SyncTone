package dev.salt.replica

import android.content.Intent

data class QaRequest(val sequence: Long, val position: Long, val page: Int, val expanded: Boolean,
                     val playing: Boolean, val flowing: Boolean, val blur: Boolean) {
    companion object {
        fun from(intent: Intent): QaRequest? = if ((BuildConfig.DEBUG || BuildConfig.QA_ENABLED) && intent.hasExtra("qa_position"))
            QaRequest(System.nanoTime(), intent.getLongExtra("qa_position", 0), intent.getIntExtra("qa_page", 1),
                intent.getBooleanExtra("qa_expanded", true), intent.getBooleanExtra("qa_playing", false),
                intent.getBooleanExtra("qa_flowing", true), intent.getBooleanExtra("qa_blur", true)) else null
    }
}
