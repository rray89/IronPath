package com.example.ironpath.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ironpath.data.local.entity.PlannedWorkout
import com.example.ironpath.data.local.entity.WeeklyPlan
import com.example.ironpath.data.local.entity.WorkoutStatus
import com.example.ironpath.ui.components.GreenGradientButton
import com.example.ironpath.ui.testing.TestTags
import com.example.ironpath.ui.theme.IronPathTheme
import com.example.ironpath.ui.theme.SurfaceContainerHigh
import com.example.ironpath.ui.theme.SurfaceContainerLow

// -- Production entry point (Hilt-backed) --

@Composable
fun HomeScreen(
    onNavigateToPlan: () -> Unit,
    onNavigateToActive: () -> Unit,
    onOpenWorkoutPreview: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    HomeContent(uiState, onNavigateToPlan, onNavigateToActive, onOpenWorkoutPreview, modifier)
}

// -- Pure render composable (no ViewModel, previewable) --

@Composable
internal fun HomeContent(
    uiState: HomeUiState,
    onNavigateToPlan: () -> Unit,
    onNavigateToActive: () -> Unit,
    onOpenWorkoutPreview: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (uiState) {
        HomeUiState.Loading -> {
            Box(
                modifier.fillMaxSize().testTag(TestTags.HOME_LOADING),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        }
        HomeUiState.NoPlan -> HomeEmptyState(onNavigateToPlan, modifier)
        is HomeUiState.ActivePlan ->
            HomeActivePlanState(uiState, onNavigateToActive, onOpenWorkoutPreview, modifier)
        is HomeUiState.WeekComplete -> HomeWeekCompleteState(uiState, onNavigateToPlan, modifier)
    }
}

// -- State composables --

@Composable
private fun HomeEmptyState(
    onNavigateToPlan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(24.dp))

        Text(
            text = "No workout plan yet",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(Modifier.height(12.dp))

        Text(
            text = "Set your workout days and create your week in the Plan tab.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(24.dp))

        GreenGradientButton(
            text = "Open Plan",
            onClick = onNavigateToPlan,
            modifier = Modifier.fillMaxWidth(0.5f),
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.CalendarMonth,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.surface,
                )
            },
        )

        Spacer(Modifier.height(40.dp))

        Box(
            modifier =
                Modifier.fillMaxWidth()
                    .background(SurfaceContainerLow, RoundedCornerShape(4.dp))
                    .padding(20.dp),
        ) {
            Row {
                Icon(
                    imageVector = Icons.Default.CalendarMonth,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(
                        text = "START IN PLAN",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text =
                            "Use the Plan tab to choose your workout days, generate a 1-week plan, and review it before saving.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeActivePlanState(
    state: HomeUiState.ActivePlan,
    onNavigateToActive: () -> Unit,
    onOpenWorkoutPreview: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(16.dp))

        // This Week summary card
        Box(
            modifier =
                Modifier.fillMaxWidth()
                    .background(SurfaceContainerLow, RoundedCornerShape(4.dp))
                    .padding(20.dp),
        ) {
            Column {
                Text(
                    text = "THIS WEEK",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${state.planned} WORKOUTS PLANNED  •  ${state.completed} COMPLETED",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )

                if (state.nextWorkout != null) {
                    Spacer(Modifier.height(12.dp))
                    val prefix = if (state.todayWorkout != null) "Today" else "Next workout"
                    Text(
                        text =
                            "$prefix: ${dayOfWeekAbbrev(state.nextWorkout.dayOfWeek)}  •  ${state.nextWorkout.title}",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                Spacer(Modifier.height(16.dp))

                if (state.hasActiveSession) {
                    Text(
                        text = "Workout in progress",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(8.dp))
                    GreenGradientButton(
                        text = "Return to Active Session",
                        onClick = onNavigateToActive,
                    )
                } else if (state.todayWorkout != null) {
                    GreenGradientButton(
                        text = "Start Workout",
                        onClick = onNavigateToActive,
                        trailingIcon = {
                            Icon(
                                imageVector = Icons.Default.FlashOn,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.surface,
                            )
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        // Stats row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            StatItem("PLANNED", state.planned.toString())
            StatItem("DONE", state.completed.toString())
            val nextDow = state.nextWorkout?.dayOfWeek?.let { dayOfWeekAbbrev(it) } ?: "—"
            StatItem("NEXT", nextDow, valueColor = MaterialTheme.colorScheme.primary)
        }

        Spacer(Modifier.height(32.dp))

        // Up Next section
        val upcomingWorkouts = state.workouts.filter { it.status.name == "Upcoming" }
        if (upcomingWorkouts.isNotEmpty()) {
            Text(
                text = "UP NEXT",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            upcomingWorkouts.forEach { workout ->
                WorkoutCard(workout = workout, onClick = { onOpenWorkoutPreview(workout.id) })
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun HomeWeekCompleteState(
    state: HomeUiState.WeekComplete,
    onNavigateToPlan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .testTag(TestTags.HOME_WEEK_COMPLETE)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(24.dp))

        Text(
            text = "Week complete!",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(Modifier.height(12.dp))

        Text(
            text = "You finished all ${state.completed} workouts this week.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(24.dp))

        GreenGradientButton(
            text = "Plan Next Week",
            onClick = onNavigateToPlan,
        )
    }
}

// -- Shared sub-components --

@Composable
private fun StatItem(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.Start) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.headlineMedium,
            color = valueColor,
        )
    }
}

@Composable
private fun WorkoutCard(
    workout: PlannedWorkout,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .testTag(TestTags.workout(workout.id))
                .clip(RoundedCornerShape(4.dp))
                .background(SurfaceContainerLow)
                .clickable(onClick = onClick)
                .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier.background(SurfaceContainerHigh, RoundedCornerShape(4.dp))
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

        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = workout.title.uppercase(),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.width(6.dp))
            Icon(
                imageVector = Icons.Default.FlashOn,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// -- Previews --

private val PreviewPlan =
    WeeklyPlan(
        id = "preview-plan",
        startDate = "2026-03-30",
        endDate = "2026-04-05",
        createdAt = 1L,
    )

private val PreviewWorkouts =
    listOf(
        PlannedWorkout(
            id = "w1",
            weeklyPlanId = "preview-plan",
            dayOfWeek = 1,
            scheduledDate = "2026-03-30",
            title = "Push A",
            status = WorkoutStatus.Completed,
        ),
        PlannedWorkout(
            id = "w2",
            weeklyPlanId = "preview-plan",
            dayOfWeek = 3,
            scheduledDate = "2026-04-01",
            title = "Pull B",
            status = WorkoutStatus.Upcoming,
        ),
        PlannedWorkout(
            id = "w3",
            weeklyPlanId = "preview-plan",
            dayOfWeek = 5,
            scheduledDate = "2026-04-03",
            title = "Legs",
            status = WorkoutStatus.Upcoming,
        ),
    )

@Preview(showBackground = true)
@Composable
private fun PreviewHomeNoPlan() {
    IronPathTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            HomeContent(
                uiState = HomeUiState.NoPlan,
                onNavigateToPlan = {},
                onNavigateToActive = {},
                onOpenWorkoutPreview = {},
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewHomeActivePlanTodayWorkout() {
    IronPathTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            HomeContent(
                uiState =
                    HomeUiState.ActivePlan(
                        plan = PreviewPlan,
                        workouts = PreviewWorkouts,
                        planned = 3,
                        completed = 1,
                        todayWorkout = PreviewWorkouts[1],
                        nextWorkout = PreviewWorkouts[1],
                        hasActiveSession = false,
                    ),
                onNavigateToPlan = {},
                onNavigateToActive = {},
                onOpenWorkoutPreview = {},
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewHomeActivePlanNextWorkout() {
    IronPathTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            HomeContent(
                uiState =
                    HomeUiState.ActivePlan(
                        plan = PreviewPlan,
                        workouts = PreviewWorkouts,
                        planned = 3,
                        completed = 1,
                        todayWorkout = null,
                        nextWorkout = PreviewWorkouts[1],
                        hasActiveSession = false,
                    ),
                onNavigateToPlan = {},
                onNavigateToActive = {},
                onOpenWorkoutPreview = {},
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewHomeWeekComplete() {
    IronPathTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            HomeContent(
                uiState = HomeUiState.WeekComplete(planned = 3, completed = 3),
                onNavigateToPlan = {},
                onNavigateToActive = {},
                onOpenWorkoutPreview = {},
            )
        }
    }
}
