package com.example.ironpath.data.repository

import com.example.ironpath.data.local.entity.PersonalRecord
import com.example.ironpath.data.local.entity.RecordSource
import java.time.Instant
import java.time.ZoneId

/** A weight-only record identity deliberately ignores reps, set ID, and provenance. */
data class LoggedRecordCandidate(
    val setId: String,
    val exerciseName: String,
    val weightKg: Double,
    val achievedOn: String,
    val note: String,
) {
    val normalizedExerciseName: String
        get() = exerciseName.trim().lowercase()
}

fun WorkoutLogDetail.recordCandidates(zoneId: ZoneId): List<LoggedRecordCandidate> {
    val date = Instant.ofEpochMilli(log.completedAt).atZone(zoneId).toLocalDate().toString()
    return exercises.flatMap { detail ->
        val name = detail.exercise.name.trim()
        detail.sets.mapNotNull { set ->
            val weight = set.weightKg
            if (
                name.isEmpty() ||
                    set.reps == null ||
                    weight == null ||
                    !weight.isFinite() ||
                    weight <= 0.0
            )
                null
            else
                LoggedRecordCandidate(
                    set.id,
                    name,
                    weight,
                    date,
                    "${log.title} · set ${set.setNumber}, ${set.reps} reps"
                )
        }
    }
}

fun WorkoutLogDetail.savedRecordSetIds(records: List<PersonalRecord>, zoneId: ZoneId): Set<String> {
    val sourceRecords =
        records.filter { it.sourceType == RecordSource.Logged && it.sourceWorkoutLogId == log.id }
    return recordCandidates(zoneId)
        .filter { candidate ->
            sourceRecords.any {
                it.normalizedExerciseName == candidate.normalizedExerciseName &&
                    it.achievedOn == candidate.achievedOn &&
                    it.weightKg == candidate.weightKg
            }
        }
        .map { it.setId }
        .toSet()
}
