package com.example.ironpath.domain.planner

import com.example.ironpath.testutil.FakeIdProvider
import com.example.ironpath.testutil.FakeTimeProvider
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanGeneratorTest {

    private val timeProvider = FakeTimeProvider()
    private val exerciseCatalog = DefaultExerciseCatalog()
    private val planFactory = RuleBasedPlanFactory(exerciseCatalog)
    private val entityMapper = PlanEntityMapper(FakeIdProvider(), timeProvider, exerciseCatalog)
    private val generator = PlanGenerator(timeProvider, planFactory, entityMapper)

    private val nextMonday: LocalDate =
        timeProvider.today().with(TemporalAdjusters.next(DayOfWeek.MONDAY))

    @Test
    fun `generate uses injected date timestamp and stable ids`() {
        val timeProvider =
            FakeTimeProvider(
                instant = Instant.parse("2026-07-16T19:00:00Z"),
                zoneId = ZoneId.of("America/Vancouver"),
            )
        val result =
            PlanGenerator(
                    timeProvider,
                    planFactory,
                    PlanEntityMapper(FakeIdProvider(), timeProvider, exerciseCatalog),
                )
                .generate(
                    goal = TrainingGoal.Strength,
                    selectedDays = setOf(1),
                )

        assertEquals("test-id-1", result.plan.id)
        assertEquals("2026-07-20", result.plan.startDate)
        assertEquals(timeProvider.epochMillis(), result.plan.createdAt)
        assertEquals("test-id-2", result.workouts.single().id)
        assertEquals(listOf("test-id-3", "test-id-4", "test-id-5"), result.exercises.map { it.id })
    }

    // -- workout count --

    @Test
    fun `generate returns one workout per selected day`() {
        val result = generator.generate(TrainingGoal.Strength, setOf(1, 3, 5))
        assertEquals(3, result.workouts.size)
    }

    @Test
    fun `generate with single day returns one workout`() {
        val result = generator.generate(TrainingGoal.Hypertrophy, setOf(2))
        assertEquals(1, result.workouts.size)
    }

    @Test
    fun `generate with no selected days preserves an empty weekly plan`() {
        val result = generator.generate(TrainingGoal.Hypertrophy, emptySet())

        assertTrue(result.workouts.isEmpty())
        assertTrue(result.exercises.isEmpty())
    }

    @Test
    fun `generate with all seven days returns seven workouts`() {
        val result = generator.generate(TrainingGoal.Endurance, setOf(1, 2, 3, 4, 5, 6, 7))
        assertEquals(7, result.workouts.size)
    }

    // -- day assignment --

    @Test
    fun `generate assigns correct dayOfWeek to each workout`() {
        val days = setOf(1, 4, 7) // Mon, Thu, Sun
        val result = generator.generate(TrainingGoal.Strength, days)
        val assignedDays = result.workouts.map { it.dayOfWeek }.toSet()
        assertEquals(days, assignedDays)
    }

    @Test
    fun `generate sorts workouts by dayOfWeek ascending`() {
        val result = generator.generate(TrainingGoal.Rehab, setOf(5, 2, 7))
        val dows = result.workouts.map { it.dayOfWeek }
        assertEquals(listOf(2, 5, 7), dows)
    }

    // -- scheduled dates --

    @Test
    fun `generate plan startDate is next Monday`() {
        val result = generator.generate(TrainingGoal.Strength, setOf(1))
        assertEquals(nextMonday.toString(), result.plan.startDate)
    }

    @Test
    fun `generate plan endDate is next Sunday`() {
        val result = generator.generate(TrainingGoal.Strength, setOf(1))
        assertEquals(nextMonday.plusDays(6).toString(), result.plan.endDate)
    }

    @Test
    fun `generate scheduledDate is nextMonday plus dayOfWeek minus one`() {
        val result = generator.generate(TrainingGoal.Strength, setOf(1, 3, 5))
        for (workout in result.workouts) {
            val expected = nextMonday.plusDays((workout.dayOfWeek - 1).toLong()).toString()
            assertEquals(expected, workout.scheduledDate)
        }
    }

    // -- template cycling --

    @Test
    fun `generate cycles Strength templates when days exceed pool size of 5`() {
        val result = generator.generate(TrainingGoal.Strength, setOf(1, 2, 3, 4, 5, 6))
        // Strength has 5 templates: index 5 should cycle back to template 0
        val titles = result.workouts.map { it.title }
        assertEquals(titles[0], titles[5]) // day 1 and day 6 should share the same template title
    }

    @Test
    fun `generate produces distinct workout titles for each TrainingGoal`() {
        val goals = TrainingGoal.entries
        val firstTitles =
            goals.map { goal -> generator.generate(goal, setOf(1)).workouts.first().title }
        // Each goal should have a different first workout title
        assertEquals(firstTitles.size, firstTitles.toSet().size)
    }

    // -- exercises --

    @Test
    fun `generate exercises belong to their correct workout`() {
        val result = generator.generate(TrainingGoal.Hypertrophy, setOf(1, 3))
        for (exercise in result.exercises) {
            val ownerWorkout = result.workouts.find { it.id == exercise.plannedWorkoutId }
            assertTrue(
                "Exercise ${exercise.name} has no matching workout",
                ownerWorkout != null,
            )
        }
    }

    @Test
    fun `generate exercise orderIndex starts at 0 and increments per workout`() {
        val result = generator.generate(TrainingGoal.Strength, setOf(1))
        val workout = result.workouts.first()
        val exercises = result.exercises.filter { it.plannedWorkoutId == workout.id }
        val indices = exercises.map { it.orderIndex }
        assertEquals((0 until exercises.size).toList(), indices)
    }

    @Test
    fun `generate total exercises equals sum of exercises per template`() {
        // Strength Push A has 3 exercises; selecting 1 day → 3 exercises
        val result = generator.generate(TrainingGoal.Strength, setOf(1))
        assertEquals(3, result.exercises.size)
    }
}
