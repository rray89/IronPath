package com.example.ironpath.ui.screens.plan

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ironpath.domain.planner.Equipment
import com.example.ironpath.domain.planner.ExerciseCautionTag
import com.example.ironpath.domain.planner.PlanValidationContext
import com.example.ironpath.domain.planner.PlanValidationLimits
import com.example.ironpath.domain.planner.PlanValidationResult
import com.example.ironpath.domain.planner.PlanValidator
import com.example.ironpath.domain.planner.PlanViolation
import com.example.ironpath.domain.planner.PlanningEngine
import com.example.ironpath.domain.planner.PlanningEngineRegistry
import com.example.ironpath.domain.planner.PlanningEngineType
import com.example.ironpath.domain.planner.PlanningFailure
import com.example.ironpath.domain.planner.PlanningGoal
import com.example.ironpath.domain.planner.PlanningHistoryProvider
import com.example.ironpath.domain.planner.PlanningIntake
import com.example.ironpath.domain.planner.PlanningRequest
import com.example.ironpath.domain.planner.PlanningResult
import com.example.ironpath.domain.planner.TrainingExperience
import com.example.ironpath.domain.planner.ValidatedPlanDraft
import com.example.ironpath.domain.time.TimeProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.DayOfWeek
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PlannerIntakeUiState(
    val goal: PlanningGoal = PlanningGoal.STRENGTH,
    val selectedDays: Set<Int> = emptySet(),
    val experience: TrainingExperience = TrainingExperience.INTERMEDIATE,
    val availableEquipment: Set<Equipment> = Equipment.entries.toSet(),
    val forbiddenCautionTags: Set<ExerciseCautionTag> = emptySet(),
    val injuryNotes: String = "",
    val exercisePreferences: String = "",
    val exerciseDislikes: String = "",
    val daySelectionMessage: String? = null,
) {
    val canGenerateRuleBased: Boolean
        get() =
            selectedDays.size in
                PlanValidationLimits.MIN_TRAINING_DAYS..PlanValidationLimits.MAX_TRAINING_DAYS &&
                selectedDays.all { it in 1..7 }

    val canGenerateWithAi: Boolean
        get() = canGenerateRuleBased && availableEquipment.isNotEmpty()

    fun toPlanningIntake() =
        PlanningIntake(
            goal = goal,
            selectedDays = selectedDays,
            experience = experience,
            availableEquipment = availableEquipment,
            forbiddenCautionTags = forbiddenCautionTags,
            injuryNotes = injuryNotes,
            exercisePreferences = exercisePreferences,
            exerciseDislikes = exerciseDislikes,
        )
}

sealed interface AiGenerationUiState {
    data object Idle : AiGenerationUiState

    data object Stale : AiGenerationUiState

    data class Generating(val requestId: Long) : AiGenerationUiState

    data class Validated(val draft: ValidatedPlanDraft) : AiGenerationUiState

    data class Invalid(val violations: List<PlanViolation>) : AiGenerationUiState

    data class Failed(
        val failure: PlanningFailure,
    ) : AiGenerationUiState
}

@HiltViewModel
class PlannerIntakeViewModel
@Inject
constructor(
    private val savedStateHandle: SavedStateHandle,
    private val planningEngineRegistry: PlanningEngineRegistry,
    private val planValidator: PlanValidator,
    private val planningHistoryProvider: PlanningHistoryProvider,
    private val timeProvider: TimeProvider,
) : ViewModel() {
    private val _intakeState = MutableStateFlow(restoredState())
    val intakeState: StateFlow<PlannerIntakeUiState> = _intakeState.asStateFlow()

    private val _aiGenerationState = MutableStateFlow<AiGenerationUiState>(AiGenerationUiState.Idle)
    val aiGenerationState: StateFlow<AiGenerationUiState> = _aiGenerationState.asStateFlow()

    private var generationJob: Job? = null
    private var currentRequestId = 0L

    val aiAvailable: Boolean = preferredAiEngine() != null

    fun setGoal(goal: PlanningGoal) = updateIntake { copy(goal = goal) }

    fun setExperience(experience: TrainingExperience) = updateIntake {
        copy(experience = experience)
    }

    fun toggleDay(day: Int) {
        if (day !in 1..7) return
        val current = _intakeState.value
        when {
            day in current.selectedDays ->
                updateIntake { copy(selectedDays = selectedDays - day, daySelectionMessage = null) }
            current.selectedDays.size >= PlanValidationLimits.MAX_TRAINING_DAYS ->
                _intakeState.update {
                    it.copy(
                        daySelectionMessage =
                            "Choose up to six workout days so the week keeps a rest day."
                    )
                }
            else ->
                updateIntake { copy(selectedDays = selectedDays + day, daySelectionMessage = null) }
        }
    }

    fun toggleEquipment(equipment: Equipment) = updateIntake {
        copy(
            availableEquipment =
                if (equipment in availableEquipment) availableEquipment - equipment
                else availableEquipment + equipment
        )
    }

    fun toggleCautionTag(tag: ExerciseCautionTag) = updateIntake {
        copy(
            forbiddenCautionTags =
                if (tag in forbiddenCautionTags) forbiddenCautionTags - tag
                else forbiddenCautionTags + tag
        )
    }

    fun setInjuryNotes(notes: String) = updateIntake {
        copy(injuryNotes = notes.take(MAX_NOTES_LENGTH))
    }

    fun setExercisePreferences(preferences: String) = updateIntake {
        copy(exercisePreferences = preferences.take(MAX_PREFERENCE_LENGTH))
    }

    fun setExerciseDislikes(dislikes: String) = updateIntake {
        copy(exerciseDislikes = dislikes.take(MAX_PREFERENCE_LENGTH))
    }

    fun generateWithAi() {
        val engine = preferredAiEngine()
        if (engine == null) {
            _aiGenerationState.value = AiGenerationUiState.Failed(PlanningFailure.Unavailable)
            return
        }
        val intakeSnapshot = _intakeState.value
        if (!intakeSnapshot.canGenerateWithAi) {
            _aiGenerationState.value =
                AiGenerationUiState.Failed(
                    PlanningFailure.InvalidRequest(
                        listOf("Choose one to six workout days and at least one equipment option")
                    )
                )
            return
        }

        generationJob?.cancel()
        val requestId = ++currentRequestId
        _aiGenerationState.value = AiGenerationUiState.Generating(requestId)
        generationJob =
            viewModelScope.launch {
                try {
                    val today = timeProvider.today()
                    val targetMonday = today.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
                    val recentTraining = planningHistoryProvider.loadRecent(today)
                    val request =
                        PlanningRequest(
                            targetWeekStart = targetMonday,
                            intake =
                                intakeSnapshot
                                    .toPlanningIntake()
                                    .copy(recentTraining = recentTraining),
                        )
                    val result = engine.generate(request)
                    if (requestId != currentRequestId) return@launch
                    _aiGenerationState.value = result.toUiState(request, engine)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Exception) {
                    if (requestId == currentRequestId) {
                        _aiGenerationState.value =
                            AiGenerationUiState.Failed(PlanningFailure.ProviderError(error.message))
                    }
                }
            }
    }

    fun cancelGeneration() {
        if (_aiGenerationState.value is AiGenerationUiState.Generating) {
            resetGenerationState()
        }
    }

    fun clearGeneratedDraft() {
        if (_aiGenerationState.value !is AiGenerationUiState.Generating) {
            _aiGenerationState.value = AiGenerationUiState.Idle
        }
    }

    fun resetAfterAcceptance() {
        resetGenerationState()
        _intakeState.value = PlannerIntakeUiState()
        persist(_intakeState.value)
    }

    private fun PlanningResult.toUiState(
        request: PlanningRequest,
        engine: PlanningEngine,
    ): AiGenerationUiState =
        when (this) {
            is PlanningResult.Failure -> AiGenerationUiState.Failed(reason)
            is PlanningResult.Success -> {
                when (
                    val validation =
                        planValidator.validate(
                            draft,
                            PlanValidationContext(
                                expectedTargetWeekStart = request.targetWeekStart,
                                invokedEngineType = engine.type,
                                selectedDays = request.selectedDays,
                                experience = request.intake.experience,
                                availableEquipment = request.intake.availableEquipment,
                                forbiddenCautionTags = request.intake.forbiddenCautionTags,
                                recentExerciseLoads = request.intake.recentTraining.exerciseLoads,
                            ),
                        )
                ) {
                    is PlanValidationResult.Valid ->
                        AiGenerationUiState.Validated(validation.validatedPlan)
                    is PlanValidationResult.Invalid ->
                        AiGenerationUiState.Invalid(validation.violations)
                }
            }
        }

    private fun preferredAiEngine(): PlanningEngine? =
        preferredAiTypes.firstNotNullOfOrNull(planningEngineRegistry::find)

    private fun updateIntake(transform: PlannerIntakeUiState.() -> PlannerIntakeUiState) {
        val updated = _intakeState.value.transform()
        if (updated == _intakeState.value) return
        _intakeState.value = updated
        persist(updated)
        invalidateGenerationForIntakeChange()
    }

    private fun invalidateGenerationForIntakeChange() {
        val previousState = _aiGenerationState.value
        currentRequestId += 1
        generationJob?.cancel()
        generationJob = null
        _aiGenerationState.value =
            when (previousState) {
                AiGenerationUiState.Idle,
                is AiGenerationUiState.Failed -> AiGenerationUiState.Idle
                AiGenerationUiState.Stale,
                is AiGenerationUiState.Generating,
                is AiGenerationUiState.Invalid,
                is AiGenerationUiState.Validated -> AiGenerationUiState.Stale
            }
    }

    private fun resetGenerationState() {
        currentRequestId += 1
        generationJob?.cancel()
        generationJob = null
        _aiGenerationState.value = AiGenerationUiState.Idle
    }

    private fun persist(state: PlannerIntakeUiState) {
        savedStateHandle[KEY_GOAL] = state.goal.name
        savedStateHandle[KEY_DAYS] = ArrayList(state.selectedDays.sorted())
        savedStateHandle[KEY_EXPERIENCE] = state.experience.name
        savedStateHandle[KEY_EQUIPMENT] = ArrayList(state.availableEquipment.map { it.name })
        savedStateHandle[KEY_CAUTIONS] = ArrayList(state.forbiddenCautionTags.map { it.name })
        savedStateHandle[KEY_INJURY_NOTES] = state.injuryNotes
        savedStateHandle[KEY_PREFERENCES] = state.exercisePreferences
        savedStateHandle[KEY_DISLIKES] = state.exerciseDislikes
    }

    private fun restoredState(): PlannerIntakeUiState {
        val defaults = PlannerIntakeUiState()
        return defaults.copy(
            goal = enumValueOrDefault(savedStateHandle[KEY_GOAL], defaults.goal),
            selectedDays =
                savedStateHandle.get<List<Int>>(KEY_DAYS).orEmpty().filter { it in 1..7 }.toSet(),
            experience = enumValueOrDefault(savedStateHandle[KEY_EXPERIENCE], defaults.experience),
            availableEquipment =
                restoredEnumSet<Equipment>(KEY_EQUIPMENT) ?: defaults.availableEquipment,
            forbiddenCautionTags = restoredEnumSet<ExerciseCautionTag>(KEY_CAUTIONS).orEmpty(),
            injuryNotes = savedStateHandle[KEY_INJURY_NOTES] ?: "",
            exercisePreferences = savedStateHandle[KEY_PREFERENCES] ?: "",
            exerciseDislikes = savedStateHandle[KEY_DISLIKES] ?: "",
        )
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(name: String?, default: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: default

    private inline fun <reified T : Enum<T>> restoredEnumSet(key: String): Set<T>? =
        savedStateHandle
            .get<List<String>>(key)
            ?.mapNotNull { name -> enumValues<T>().firstOrNull { it.name == name } }
            ?.toSet()

    private companion object {
        val preferredAiTypes =
            listOf(
                PlanningEngineType.ON_DEVICE_AI,
                PlanningEngineType.DEBUG_FAKE_AI,
                PlanningEngineType.DEBUG_REMOTE_AI,
            )
        const val MAX_NOTES_LENGTH = 500
        const val MAX_PREFERENCE_LENGTH = 300
        const val KEY_GOAL = "planner_intake_goal"
        const val KEY_DAYS = "planner_intake_days"
        const val KEY_EXPERIENCE = "planner_intake_experience"
        const val KEY_EQUIPMENT = "planner_intake_equipment"
        const val KEY_CAUTIONS = "planner_intake_cautions"
        const val KEY_INJURY_NOTES = "planner_intake_injury_notes"
        const val KEY_PREFERENCES = "planner_intake_preferences"
        const val KEY_DISLIKES = "planner_intake_dislikes"
    }
}
