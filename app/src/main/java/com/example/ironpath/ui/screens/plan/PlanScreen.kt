package com.example.ironpath.ui.screens.plan

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ironpath.data.local.entity.PlannedExercise
import com.example.ironpath.data.local.entity.PlannedWorkout
import com.example.ironpath.data.local.entity.WeeklyPlan
import com.example.ironpath.data.local.entity.WorkoutStatus
import com.example.ironpath.domain.planner.GeneratedPlan
import com.example.ironpath.domain.planner.TrainingGoal
import com.example.ironpath.ui.components.GreenGradientButton
import com.example.ironpath.ui.screens.home.dayOfWeekAbbrev
import com.example.ironpath.ui.theme.IronPathTheme
import com.example.ironpath.ui.theme.SurfaceContainerHigh
import com.example.ironpath.ui.theme.SurfaceContainerLow
import org.koin.androidx.compose.koinViewModel

// -- Production entry point --

@Composable
fun PlanScreen(
    onPlanAccepted: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlanViewModel = koinViewModel(),
) {
    val uiState by viewModel.planUiState.collectAsStateWithLifecycle()
    val selectedGoal by viewModel.selectedGoal.collectAsStateWithLifecycle()
    val selectedDays by viewModel.selectedDays.collectAsStateWithLifecycle()

    PlanContent(
        uiState = uiState,
        selectedGoal = selectedGoal,
        selectedDays = selectedDays,
        onGoalSelected = viewModel::setGoal,
        onDayToggled = viewModel::toggleDay,
        onGenerate = viewModel::generatePlan,
        onDeleteWorkout = viewModel::deleteWorkoutFromReview,
        onRegenerate = viewModel::regenerate,
        onBackToSetup = viewModel::backToSetup,
        onAccept = { viewModel.acceptPlan(onPlanAccepted) },
        modifier = modifier,
    )
}

// -- Pure render composable --

@Composable
internal fun PlanContent(
    uiState: PlanUiState,
    selectedGoal: TrainingGoal,
    selectedDays: Set<Int>,
    onGoalSelected: (TrainingGoal) -> Unit,
    onDayToggled: (Int) -> Unit,
    onGenerate: () -> Unit,
    onDeleteWorkout: (String) -> Unit,
    onRegenerate: () -> Unit,
    onBackToSetup: () -> Unit,
    onAccept: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (uiState) {
        PlanUiState.Loading -> {
            Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        }
        PlanUiState.Setup -> PlanSetupScreen(
            selectedGoal = selectedGoal,
            selectedDays = selectedDays,
            onGoalSelected = onGoalSelected,
            onDayToggled = onDayToggled,
            onGenerate = onGenerate,
            modifier = modifier,
        )
        is PlanUiState.Review -> PlanReviewScreen(
            generated = uiState.generated,
            onDeleteWorkout = onDeleteWorkout,
            onRegenerate = onRegenerate,
            onBackToSetup = onBackToSetup,
            onAccept = onAccept,
            modifier = modifier,
        )
        is PlanUiState.Accepted -> PlanAcceptedScreen(
            plan = uiState.plan,
            workouts = uiState.workouts,
            modifier = modifier,
        )
    }
}

// -- Setup Screen --

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlanSetupScreen(
    selectedGoal: TrainingGoal,
    selectedDays: Set<Int>,
    onGoalSelected: (TrainingGoal) -> Unit,
    onDayToggled: (Int) -> Unit,
    onGenerate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(16.dp))

        // Primary Goal
        Text(
            text = "Primary Goal",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(Modifier.height(12.dp))

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TrainingGoal.entries.forEach { goal ->
                GoalChip(
                    label = goal.name.uppercase(),
                    selected = goal == selectedGoal,
                    onClick = { onGoalSelected(goal) },
                )
            }
        }

        Spacer(Modifier.height(32.dp))

        // Workout Days
        Text(
            text = "Workout Days",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val dayLabels = listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")
            dayLabels.forEachIndexed { index, label ->
                val dow = index + 1
                DayChip(
                    label = label,
                    selected = dow in selectedDays,
                    onClick = { onDayToggled(dow) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        Text(
            text = "SELECT THE DAYS YOU WANT TO TRAIN THIS WEEK",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.weight(1f))

        // Generate button
        GreenGradientButton(
            text = "Generate Week",
            onClick = onGenerate,
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.CalendarMonth,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.surface,
                )
            },
        )

        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun GoalChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(4.dp)
    val bgColor = if (selected) MaterialTheme.colorScheme.primary else SurfaceContainerHigh
    val textColor = if (selected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface

    Box(
        modifier = modifier
            .clip(shape)
            .background(bgColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (selected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = textColor,
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = textColor,
            )
        }
    }
}

@Composable
private fun DayChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(4.dp)
    val bgColor = if (selected) MaterialTheme.colorScheme.primary else SurfaceContainerHigh
    val textColor = if (selected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface

    Box(
        modifier = modifier
            .clip(shape)
            .background(bgColor)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = textColor,
        )
    }
}

// -- Review Screen --

@Composable
private fun PlanReviewScreen(
    generated: GeneratedPlan,
    onDeleteWorkout: (String) -> Unit,
    onRegenerate: () -> Unit,
    onBackToSetup: () -> Unit,
    onAccept: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(16.dp))

        Text(
            text = "WEEKLY PLAN",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = "THIS WEEK",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(Modifier.height(24.dp))

        // Workouts grouped by day
        generated.workouts.forEach { workout ->
            val exercises = generated.exercises.filter { it.plannedWorkoutId == workout.id }
            ReviewWorkoutCard(
                workout = workout,
                exercises = exercises,
                onDelete = { onDeleteWorkout(workout.id) },
            )
            Spacer(Modifier.height(20.dp))
        }

        Spacer(Modifier.weight(1f))

        // Bottom action row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Regenerate button
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(4.dp))
                    .background(SurfaceContainerHigh)
                    .clickable(onClick = onRegenerate)
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "REGENERATE",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            // Accept button
            GreenGradientButton(
                text = "Accept Plan",
                onClick = onAccept,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun ReviewWorkoutCard(
    workout: PlannedWorkout,
    exercises: List<PlannedExercise>,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        // Day header with delete button
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${dayOfWeekAbbrev(workout.dayOfWeek)} - ${workout.title}",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Remove workout",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // Exercises
        exercises.forEach { exercise ->
            ReviewExerciseRow(exercise)
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun ReviewExerciseRow(
    exercise: PlannedExercise,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = exercise.name,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        val weightText = if (exercise.weightKg > 0) "${exercise.weightKg.toInt()}KG" else "BW"
        Text(
            text = "${exercise.sets} SETS  •  ${exercise.reps} REPS  •  $weightText",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// -- Accepted/Summary Screen --

@Composable
private fun PlanAcceptedScreen(
    plan: WeeklyPlan,
    workouts: List<PlannedWorkout>,
    modifier: Modifier = Modifier,
) {
    val planned = workouts.size
    val completed = workouts.count { it.status == WorkoutStatus.Completed }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(16.dp))

        Text(
            text = "CURRENT PLAN",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = "This Week",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = "${plan.startDate} — ${plan.endDate}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(4.dp))

        Text(
            text = "$planned workouts planned  •  $completed completed",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )

        Spacer(Modifier.height(24.dp))

        // Workout list
        workouts.forEach { workout ->
            AcceptedWorkoutRow(workout)
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun AcceptedWorkoutRow(
    workout: PlannedWorkout,
    modifier: Modifier = Modifier,
) {
    val statusColor = when (workout.status) {
        WorkoutStatus.Completed -> MaterialTheme.colorScheme.primary
        WorkoutStatus.Skipped -> MaterialTheme.colorScheme.onSurfaceVariant
        WorkoutStatus.Upcoming -> MaterialTheme.colorScheme.onSurface
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(SurfaceContainerLow, RoundedCornerShape(4.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .background(SurfaceContainerHigh, RoundedCornerShape(4.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = dayOfWeekAbbrev(workout.dayOfWeek),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.width(16.dp))

        Text(
            text = workout.title,
            style = MaterialTheme.typography.titleSmall,
            color = statusColor,
            modifier = Modifier.weight(1f),
        )

        Text(
            text = workout.status.name.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = statusColor,
        )
    }
}

// -- Previews --

private val PreviewPlan = WeeklyPlan(
    id = "preview-plan",
    startDate = "2026-03-30",
    endDate = "2026-04-05",
)

private val PreviewWorkouts = listOf(
    PlannedWorkout("w1", "preview-plan", 1, "2026-03-30", "Chest/Tris", WorkoutStatus.Completed),
    PlannedWorkout("w2", "preview-plan", 3, "2026-04-01", "Back/Bis", WorkoutStatus.Upcoming),
    PlannedWorkout("w3", "preview-plan", 5, "2026-04-03", "Legs", WorkoutStatus.Upcoming),
)

private val PreviewExercises = listOf(
    PlannedExercise(plannedWorkoutId = "w1", name = "Barbell Bench Press", sets = 4, reps = 10, weightKg = 50.0, orderIndex = 0),
    PlannedExercise(plannedWorkoutId = "w1", name = "Dumbbell Incline Flys", sets = 3, reps = 12, weightKg = 12.0, orderIndex = 1),
    PlannedExercise(plannedWorkoutId = "w1", name = "Tricep Rope Pushdowns", sets = 3, reps = 12, weightKg = 15.0, orderIndex = 2),
    PlannedExercise(plannedWorkoutId = "w2", name = "Barbell Rows", sets = 4, reps = 10, weightKg = 50.0, orderIndex = 0),
    PlannedExercise(plannedWorkoutId = "w2", name = "Lat Pulldowns", sets = 3, reps = 12, weightKg = 45.0, orderIndex = 1),
    PlannedExercise(plannedWorkoutId = "w3", name = "Barbell Squats", sets = 4, reps = 10, weightKg = 60.0, orderIndex = 0),
)

@Preview(showBackground = true)
@Composable
private fun PreviewPlanSetup() {
    IronPathTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            PlanContent(
                uiState = PlanUiState.Setup,
                selectedGoal = TrainingGoal.Strength,
                selectedDays = setOf(1, 3, 5),
                onGoalSelected = {}, onDayToggled = {}, onGenerate = {},
                onDeleteWorkout = {}, onRegenerate = {}, onBackToSetup = {},
                onAccept = {},
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewPlanReview() {
    IronPathTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            PlanContent(
                uiState = PlanUiState.Review(
                    GeneratedPlan(PreviewPlan, PreviewWorkouts, PreviewExercises),
                ),
                selectedGoal = TrainingGoal.Hypertrophy,
                selectedDays = setOf(1, 3, 5),
                onGoalSelected = {}, onDayToggled = {}, onGenerate = {},
                onDeleteWorkout = {}, onRegenerate = {}, onBackToSetup = {},
                onAccept = {},
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewPlanAccepted() {
    IronPathTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            PlanContent(
                uiState = PlanUiState.Accepted(PreviewPlan, PreviewWorkouts),
                selectedGoal = TrainingGoal.Strength,
                selectedDays = emptySet(),
                onGoalSelected = {}, onDayToggled = {}, onGenerate = {},
                onDeleteWorkout = {}, onRegenerate = {}, onBackToSetup = {},
                onAccept = {},
            )
        }
    }
}
