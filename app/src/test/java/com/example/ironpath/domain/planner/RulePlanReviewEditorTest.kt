package com.example.ironpath.domain.planner

import com.example.ironpath.data.local.entity.PlannedExercise
import com.example.ironpath.data.local.entity.PlannedWorkout
import com.example.ironpath.data.local.entity.WeeklyPlan
import com.example.ironpath.testutil.FakeIdProvider
import org.junit.Assert.*
import org.junit.Test

class RulePlanReviewEditorTest {
    private val editor = RulePlanReviewEditor(FakeIdProvider())
    private val week =
        WeeklyPlan("week", startDate = "2026-12-28", endDate = "2027-01-03", createdAt = 1)
    private val monday = PlannedWorkout("monday", week.id, 1, "2026-12-28", "Push A")
    private val wednesday = PlannedWorkout("wednesday", week.id, 3, "2026-12-30", "Pull A")
    private val first = PlannedExercise("first", monday.id, "Bench", 3, 10, 20.5, 0)
    private val second = PlannedExercise("second", monday.id, "Bench", 4, 8, 10.0, 1)
    private val last = PlannedExercise("last", wednesday.id, "Rows", 1, 1, 0.0, 0)
    private val plan = GeneratedPlan(week, listOf(monday, wednesday), listOf(first, second, last))

    private fun children(p: GeneratedPlan, id: String) =
        p.exercises.filter { it.plannedWorkoutId == id }.sortedBy { it.orderIndex }

    private val form = RuleExerciseForm(" Custom lift ", "20", "100", "0.5")

    @Test
    fun `move to empty day crosses year and preserves graph identity`() {
        val moved = editor.moveWorkout(plan, monday.id, 7)
        assertEquals(listOf(3, 7), moved.workouts.map { it.dayOfWeek })
        assertEquals(
            monday.copy(dayOfWeek = 7, scheduledDate = "2027-01-03"),
            moved.workouts.last()
        )
        assertEquals(plan.exercises, moved.exercises)
        assertEquals(week, moved.plan)
        assertEquals("2026-12-28", plan.workouts.first().scheduledDate)
    }

    @Test
    fun `occupied day swaps dates and titles without duplicate days`() {
        val moved = editor.moveWorkout(plan, monday.id, 3)
        assertEquals(
            listOf(
                wednesday.copy(dayOfWeek = 1, scheduledDate = "2026-12-28"),
                monday.copy(dayOfWeek = 3, scheduledDate = "2026-12-30")
            ),
            moved.workouts
        )
        assertEquals(plan.exercises, moved.exercises)
    }

    @Test
    fun `invalid same and missing day moves are no ops`() {
        for (day in listOf(-1, 0, 1, 8)) assertSame(plan, editor.moveWorkout(plan, monday.id, day))
        assertSame(plan, editor.moveWorkout(plan, "missing", 2))
    }

    @Test
    fun `editing keeps exercise identity and accepts legacy v2 boundaries`() {
        val edited = editor.editExercise(plan, first.id, form)
        assertEquals(
            first.copy(name = "Custom lift", sets = 20, reps = 100, weightKg = 0.5),
            children(edited, monday.id).first()
        )
        assertEquals(second, children(edited, monday.id).last())
        assertEquals(first, children(plan, monday.id).first())
    }

    @Test
    fun `append permits duplicate names and preserves existing UUIDs`() {
        val added = editor.addExercise(plan, monday.id, form.copy(name = "Bench"))
        assertEquals(listOf(first, second), children(added, monday.id).take(2))
        val exercise = children(added, monday.id).last()
        assertEquals("Bench", exercise.name)
        assertEquals(2, exercise.orderIndex)
        assertEquals(monday.id, exercise.plannedWorkoutId)
        assertFalse(exercise.id in plan.exercises.map { it.id })
    }

    @Test
    fun `field validation rejects malformed out of range and nonfinite values`() {
        val invalid =
            listOf(
                form.copy(name = " "),
                form.copy(sets = "0"),
                form.copy(sets = "21"),
                form.copy(sets = "1.5"),
                form.copy(reps = "0"),
                form.copy(reps = "101"),
                form.copy(reps = "no"),
                form.copy(weight = "-0.01"),
                form.copy(weight = "NaN"),
                form.copy(weight = "Infinity"),
                form.copy(weight = "")
            )
        for (input in invalid) {
            assertNull(input.toString(), input.validate().values)
            assertSame(plan, editor.editExercise(plan, first.id, input))
            assertSame(plan, editor.addExercise(plan, monday.id, input))
        }
        assertNotNull(form.copy(name = "").validate().nameError)
        assertNotNull(form.copy(sets = "21").validate().setsError)
        assertNotNull(form.copy(reps = "101").validate().repsError)
        assertNotNull(form.copy(weight = "NaN").validate().weightError)
    }

    @Test
    fun `inclusive bounds allow bodyweight and fractional weights`() {
        assertEquals(
            RuleExerciseValues("Custom lift", 1, 1, 0.0),
            form.copy(sets = "1", reps = "1", weight = "0").validate().values
        )
        assertEquals(RuleExerciseValues("Custom lift", 20, 100, 0.5), form.validate().values)
    }

    @Test
    fun `missing edit and add targets do not mutate a draft`() {
        assertSame(plan, editor.editExercise(plan, "missing", form))
        assertSame(plan, editor.addExercise(plan, "missing", form))
        assertNull(editor.removeExercise(plan, "missing"))
    }

    @Test
    fun `reordering is constrained to one workout and normalizes indices`() {
        val moved = editor.moveExercise(plan, monday.id, second.id, 0)
        assertEquals(
            listOf(second.copy(orderIndex = 0), first.copy(orderIndex = 1)),
            children(moved, monday.id)
        )
        assertEquals(listOf(last), children(moved, wednesday.id))
        assertSame(plan, editor.moveExercise(plan, monday.id, last.id, 0))
        assertSame(plan, editor.moveExercise(plan, monday.id, second.id, -1))
        assertSame(plan, editor.moveExercise(plan, monday.id, second.id, 2))
        assertSame(plan, editor.moveExercise(plan, monday.id, first.id, 0))
    }

    @Test
    fun `removing a middle exercise preserves siblings and captures one undo snapshot`() {
        val removed = requireNotNull(editor.removeExercise(plan, first.id))
        assertSame(plan, removed.before)
        assertEquals("Bench", removed.exerciseName)
        assertEquals(listOf(second.copy(orderIndex = 0)), children(removed.after, monday.id))
        assertEquals(plan.workouts, removed.after.workouts)
        assertEquals(listOf(last), children(removed.after, wednesday.id))
    }

    @Test
    fun `removing last exercise removes only its day and undo preserves UUID and date`() {
        val removed = requireNotNull(editor.removeExercise(plan, last.id))
        assertEquals(listOf(monday), removed.after.workouts)
        assertEquals(listOf(first, second), removed.after.exercises)
        assertEquals(plan, removed.before)
        assertEquals(wednesday, removed.before.workouts.last())
    }
}
