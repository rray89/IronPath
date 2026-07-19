package com.example.ironpath.domain.session

import com.example.ironpath.data.local.entity.SessionSet

object SessionSetInput {
    fun withWeight(set: SessionSet, text: String, nowMillis: Long): SessionSet {
        val weight = text.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }
        return applyCompletion(set.copy(weightKg = weight), nowMillis)
    }

    fun withReps(set: SessionSet, text: String, nowMillis: Long): SessionSet {
        val reps = text.toIntOrNull()?.takeIf { it > 0 }
        return applyCompletion(set.copy(reps = reps), nowMillis)
    }

    private fun applyCompletion(set: SessionSet, nowMillis: Long): SessionSet =
        set.copy(
            completedAt =
                if (set.reps != null && set.weightKg != null) {
                    set.completedAt ?: nowMillis
                } else {
                    null
                },
        )
}
