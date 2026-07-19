package com.example.ironpath.domain.planner

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrainingHistorySummarizerTest {
    private val summarizer = TrainingHistorySummarizer(DefaultExerciseCatalog())
    private val today = LocalDate.parse("2026-07-19")

    @Test
    fun `summarize keeps only the inclusive twenty eight day window`() {
        val summary =
            summarizer.summarize(
                today = today,
                workouts =
                    listOf(
                        workout("boundary", today.minusDays(27), "Barbell Bench Press", 70.0),
                        workout("too-old", today.minusDays(28), "Barbell Bench Press", 100.0),
                        workout("future", today.plusDays(1), "Barbell Bench Press", 110.0),
                    ),
                records =
                    listOf(
                        RecentRecordSnapshot("Barbell Bench Press", 75.0, today.minusDays(27)),
                        RecentRecordSnapshot("Barbell Bench Press", 120.0, today.minusDays(28)),
                    ),
            )

        assertEquals(listOf("boundary"), summary.workouts.map { it.title })
        assertEquals(listOf(75.0), summary.records.map { it.weightKg })
        assertEquals(
            70.0,
            summary.exerciseLoads
                .single { it.catalogId == ExerciseCatalogIds.BARBELL_BENCH_PRESS }
                .maxWeightKg,
            0.0,
        )
    }

    @Test
    fun `summarize resolves rule based names and reports unknown history without throwing`() {
        val summary =
            summarizer.summarize(
                today = today,
                workouts =
                    listOf(
                        workout("known", today, "Barbell Bench Press", 60.0),
                        workout("unknown", today, "Mystery Press", 90.0),
                    ),
                records = listOf(RecentRecordSnapshot("Mystery Curl", 20.0, today)),
            )

        assertEquals(1, summary.exerciseLoads.size)
        assertEquals(
            setOf("Mystery Press", "Mystery Curl"),
            summary.unresolvedExerciseNames,
        )
    }

    @Test
    fun `summarize uses completed sets and the highest exact exercise load`() {
        val summary =
            summarizer.summarize(
                today = today,
                workouts =
                    listOf(
                        RecentWorkoutSnapshot(
                            title = "Monday",
                            completedOn = today.minusDays(2),
                            exercises =
                                listOf(
                                    RecentLoggedExerciseSnapshot(
                                        name = "Barbell Bench Press",
                                        completedSetWeightsKg = listOf(60.0, null, 65.0),
                                    )
                                ),
                        ),
                        workout("Friday", today, "Barbell Bench Press", 67.5),
                    ),
                records = emptyList(),
            )

        assertEquals(2, summary.workouts.size)
        assertEquals(
            67.5,
            summary.exerciseLoads.single().maxWeightKg,
            0.0,
        )
        assertTrue(summary.unresolvedExerciseNames.isEmpty())
    }

    private fun workout(
        title: String,
        completedOn: LocalDate,
        exerciseName: String,
        weightKg: Double,
    ) =
        RecentWorkoutSnapshot(
            title = title,
            completedOn = completedOn,
            exercises =
                listOf(
                    RecentLoggedExerciseSnapshot(
                        name = exerciseName,
                        completedSetWeightsKg = listOf(weightKg),
                    )
                ),
        )
}
