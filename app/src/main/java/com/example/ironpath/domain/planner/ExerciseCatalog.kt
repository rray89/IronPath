package com.example.ironpath.domain.planner

import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@JvmInline value class ExerciseCatalogId(val value: String)

enum class PrimaryMuscleGroup {
    CHEST,
    SHOULDERS,
    TRICEPS,
    BACK,
    BICEPS,
    QUADRICEPS,
    HAMSTRINGS,
    CALVES,
    GLUTES,
    CORE,
    FULL_BODY,
}

enum class Equipment {
    BARBELL,
    BENCH,
    DUMBBELL,
    BODYWEIGHT,
    PULL_UP_BAR,
    CABLE_MACHINE,
    MACHINE,
    KETTLEBELL,
    RESISTANCE_BAND,
    WALL,
}

/**
 * A deterministic screening signal, not a medical contraindication. Validators may block a tag only
 * when intake explicitly forbids the matching movement or load characteristic.
 */
enum class ExerciseCautionTag {
    SHOULDER,
    KNEE,
    LOWER_BACK,
    HIGH_IMPACT,
    OVERHEAD,
}

data class ExerciseCatalogEntry(
    val id: ExerciseCatalogId,
    val displayName: String,
    val primaryMuscleGroup: PrimaryMuscleGroup,
    val requiredEquipment: Set<Equipment>,
    val beginnerSuitable: Boolean,
    val cautionTags: Set<ExerciseCautionTag> = emptySet(),
    /** False when v4's single targetWeightKg cannot encode the prescription unambiguously. */
    val allowedInAiDraft: Boolean = true,
)

interface ExerciseCatalog {
    val entries: List<ExerciseCatalogEntry>

    fun find(id: ExerciseCatalogId): ExerciseCatalogEntry?

    fun findByNormalizedName(name: String): ExerciseCatalogEntry? =
        entries.firstOrNull { normalizeExerciseName(it.displayName) == normalizeExerciseName(name) }

    fun require(id: ExerciseCatalogId): ExerciseCatalogEntry =
        checkNotNull(find(id)) { "Unknown exercise catalog id: ${id.value}" }
}

object ExerciseCatalogIds {
    val BARBELL_BENCH_PRESS = ExerciseCatalogId("barbell-bench-press")
    val OVERHEAD_PRESS = ExerciseCatalogId("overhead-press")
    val TRICEP_DIPS = ExerciseCatalogId("tricep-dips")
    val BARBELL_ROWS = ExerciseCatalogId("barbell-rows")
    val WEIGHTED_PULL_UPS = ExerciseCatalogId("weighted-pull-ups")
    val BARBELL_CURLS = ExerciseCatalogId("barbell-curls")
    val BARBELL_SQUATS = ExerciseCatalogId("barbell-squats")
    val ROMANIAN_DEADLIFT = ExerciseCatalogId("romanian-deadlift")
    val CALF_RAISES = ExerciseCatalogId("calf-raises")
    val INCLINE_DUMBBELL_PRESS = ExerciseCatalogId("incline-dumbbell-press")
    val DUMBBELL_LATERAL_RAISES = ExerciseCatalogId("dumbbell-lateral-raises")
    val TRICEP_ROPE_PUSHDOWNS = ExerciseCatalogId("tricep-rope-pushdowns")
    val DEADLIFT = ExerciseCatalogId("deadlift")
    val LAT_PULLDOWNS = ExerciseCatalogId("lat-pulldowns")
    val FACE_PULLS = ExerciseCatalogId("face-pulls")
    val DUMBBELL_INCLINE_FLYS = ExerciseCatalogId("dumbbell-incline-flys")
    val LEG_PRESS = ExerciseCatalogId("leg-press")
    val HAMMER_CURLS = ExerciseCatalogId("hammer-curls")
    val PUSH_UPS = ExerciseCatalogId("push-ups")
    val DUMBBELL_ROWS = ExerciseCatalogId("dumbbell-rows")
    val SHOULDER_PRESS = ExerciseCatalogId("shoulder-press")
    val BODYWEIGHT_SQUATS = ExerciseCatalogId("bodyweight-squats")
    val WALKING_LUNGES = ExerciseCatalogId("walking-lunges")
    val KETTLEBELL_SWINGS = ExerciseCatalogId("kettlebell-swings")
    val BURPEES = ExerciseCatalogId("burpees")
    val PLANK_HOLD = ExerciseCatalogId("plank-hold")
    val BAND_PULL_APARTS = ExerciseCatalogId("band-pull-aparts")
    val WALL_SLIDES = ExerciseCatalogId("wall-slides")
    val LIGHT_DUMBBELL_PRESS = ExerciseCatalogId("light-dumbbell-press")
    val GOBLET_SQUATS = ExerciseCatalogId("goblet-squats")
    val GLUTE_BRIDGES = ExerciseCatalogId("glute-bridges")
}

@Singleton
class DefaultExerciseCatalog @Inject constructor() : ExerciseCatalog {
    override val entries: List<ExerciseCatalogEntry> = defaultExerciseEntries
    private val entriesById = entries.associateBy(ExerciseCatalogEntry::id)
    private val entriesByName = entries.associateBy { normalizeExerciseName(it.displayName) }

    init {
        check(entriesById.size == entries.size) { "Exercise catalog ids must be unique" }
        check(entriesByName.size == entries.size) { "Exercise catalog names must be unique" }
    }

    override fun find(id: ExerciseCatalogId): ExerciseCatalogEntry? = entriesById[id]

    override fun findByNormalizedName(name: String): ExerciseCatalogEntry? =
        entriesByName[normalizeExerciseName(name)]
}

internal fun normalizeExerciseName(name: String): String =
    name.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")

private fun entry(
    id: ExerciseCatalogId,
    name: String,
    muscle: PrimaryMuscleGroup,
    equipment: Set<Equipment>,
    beginnerSuitable: Boolean,
    vararg cautions: ExerciseCautionTag,
) =
    ExerciseCatalogEntry(
        id = id,
        displayName = name,
        primaryMuscleGroup = muscle,
        requiredEquipment = equipment,
        beginnerSuitable = beginnerSuitable,
        cautionTags = cautions.toSet(),
    )

private fun aiExcludedEntry(
    id: ExerciseCatalogId,
    name: String,
    muscle: PrimaryMuscleGroup,
    equipment: Set<Equipment>,
    beginnerSuitable: Boolean,
    vararg cautions: ExerciseCautionTag,
) = entry(id, name, muscle, equipment, beginnerSuitable, *cautions).copy(allowedInAiDraft = false)

private val defaultExerciseEntries =
    listOf(
        entry(
            ExerciseCatalogIds.BARBELL_BENCH_PRESS,
            "Barbell Bench Press",
            PrimaryMuscleGroup.CHEST,
            setOf(Equipment.BARBELL, Equipment.BENCH),
            false,
            ExerciseCautionTag.SHOULDER
        ),
        entry(
            ExerciseCatalogIds.OVERHEAD_PRESS,
            "Overhead Press",
            PrimaryMuscleGroup.SHOULDERS,
            setOf(Equipment.BARBELL),
            false,
            ExerciseCautionTag.SHOULDER,
            ExerciseCautionTag.OVERHEAD
        ),
        entry(
            ExerciseCatalogIds.TRICEP_DIPS,
            "Tricep Dips",
            PrimaryMuscleGroup.TRICEPS,
            setOf(Equipment.BODYWEIGHT),
            false,
            ExerciseCautionTag.SHOULDER
        ),
        entry(
            ExerciseCatalogIds.BARBELL_ROWS,
            "Barbell Rows",
            PrimaryMuscleGroup.BACK,
            setOf(Equipment.BARBELL),
            false,
            ExerciseCautionTag.LOWER_BACK
        ),
        aiExcludedEntry(
            ExerciseCatalogIds.WEIGHTED_PULL_UPS,
            "Weighted Pull-ups",
            PrimaryMuscleGroup.BACK,
            setOf(Equipment.PULL_UP_BAR),
            false,
            ExerciseCautionTag.SHOULDER
        ),
        entry(
            ExerciseCatalogIds.BARBELL_CURLS,
            "Barbell Curls",
            PrimaryMuscleGroup.BICEPS,
            setOf(Equipment.BARBELL),
            true
        ),
        entry(
            ExerciseCatalogIds.BARBELL_SQUATS,
            "Barbell Squats",
            PrimaryMuscleGroup.QUADRICEPS,
            setOf(Equipment.BARBELL),
            false,
            ExerciseCautionTag.KNEE,
            ExerciseCautionTag.LOWER_BACK
        ),
        entry(
            ExerciseCatalogIds.ROMANIAN_DEADLIFT,
            "Romanian Deadlift",
            PrimaryMuscleGroup.HAMSTRINGS,
            setOf(Equipment.BARBELL),
            false,
            ExerciseCautionTag.LOWER_BACK
        ),
        entry(
            ExerciseCatalogIds.CALF_RAISES,
            "Calf Raises",
            PrimaryMuscleGroup.CALVES,
            setOf(Equipment.MACHINE),
            true
        ),
        entry(
            ExerciseCatalogIds.INCLINE_DUMBBELL_PRESS,
            "Incline Dumbbell Press",
            PrimaryMuscleGroup.CHEST,
            setOf(Equipment.DUMBBELL, Equipment.BENCH),
            true,
            ExerciseCautionTag.SHOULDER
        ),
        entry(
            ExerciseCatalogIds.DUMBBELL_LATERAL_RAISES,
            "Dumbbell Lateral Raises",
            PrimaryMuscleGroup.SHOULDERS,
            setOf(Equipment.DUMBBELL),
            true,
            ExerciseCautionTag.SHOULDER
        ),
        entry(
            ExerciseCatalogIds.TRICEP_ROPE_PUSHDOWNS,
            "Tricep Rope Pushdowns",
            PrimaryMuscleGroup.TRICEPS,
            setOf(Equipment.CABLE_MACHINE),
            true
        ),
        entry(
            ExerciseCatalogIds.DEADLIFT,
            "Deadlift",
            PrimaryMuscleGroup.BACK,
            setOf(Equipment.BARBELL),
            false,
            ExerciseCautionTag.LOWER_BACK
        ),
        entry(
            ExerciseCatalogIds.LAT_PULLDOWNS,
            "Lat Pulldowns",
            PrimaryMuscleGroup.BACK,
            setOf(Equipment.CABLE_MACHINE),
            true,
            ExerciseCautionTag.SHOULDER
        ),
        entry(
            ExerciseCatalogIds.FACE_PULLS,
            "Face Pulls",
            PrimaryMuscleGroup.SHOULDERS,
            setOf(Equipment.CABLE_MACHINE),
            true,
            ExerciseCautionTag.SHOULDER
        ),
        entry(
            ExerciseCatalogIds.DUMBBELL_INCLINE_FLYS,
            "Dumbbell Incline Flys",
            PrimaryMuscleGroup.CHEST,
            setOf(Equipment.DUMBBELL, Equipment.BENCH),
            true,
            ExerciseCautionTag.SHOULDER
        ),
        entry(
            ExerciseCatalogIds.LEG_PRESS,
            "Leg Press",
            PrimaryMuscleGroup.QUADRICEPS,
            setOf(Equipment.MACHINE),
            true,
            ExerciseCautionTag.KNEE
        ),
        entry(
            ExerciseCatalogIds.HAMMER_CURLS,
            "Hammer Curls",
            PrimaryMuscleGroup.BICEPS,
            setOf(Equipment.DUMBBELL),
            true
        ),
        entry(
            ExerciseCatalogIds.PUSH_UPS,
            "Push-ups",
            PrimaryMuscleGroup.CHEST,
            setOf(Equipment.BODYWEIGHT),
            true,
            ExerciseCautionTag.SHOULDER
        ),
        entry(
            ExerciseCatalogIds.DUMBBELL_ROWS,
            "Dumbbell Rows",
            PrimaryMuscleGroup.BACK,
            setOf(Equipment.DUMBBELL, Equipment.BENCH),
            true,
            ExerciseCautionTag.LOWER_BACK
        ),
        entry(
            ExerciseCatalogIds.SHOULDER_PRESS,
            "Shoulder Press",
            PrimaryMuscleGroup.SHOULDERS,
            setOf(Equipment.DUMBBELL),
            true,
            ExerciseCautionTag.SHOULDER,
            ExerciseCautionTag.OVERHEAD
        ),
        entry(
            ExerciseCatalogIds.BODYWEIGHT_SQUATS,
            "Bodyweight Squats",
            PrimaryMuscleGroup.QUADRICEPS,
            setOf(Equipment.BODYWEIGHT),
            true,
            ExerciseCautionTag.KNEE
        ),
        entry(
            ExerciseCatalogIds.WALKING_LUNGES,
            "Walking Lunges",
            PrimaryMuscleGroup.QUADRICEPS,
            setOf(Equipment.BODYWEIGHT),
            true,
            ExerciseCautionTag.KNEE
        ),
        entry(
            ExerciseCatalogIds.KETTLEBELL_SWINGS,
            "Kettlebell Swings",
            PrimaryMuscleGroup.FULL_BODY,
            setOf(Equipment.KETTLEBELL),
            false,
            ExerciseCautionTag.LOWER_BACK
        ),
        entry(
            ExerciseCatalogIds.BURPEES,
            "Burpees",
            PrimaryMuscleGroup.FULL_BODY,
            setOf(Equipment.BODYWEIGHT),
            true,
            ExerciseCautionTag.HIGH_IMPACT
        ),
        entry(
            ExerciseCatalogIds.PLANK_HOLD,
            "Plank Hold",
            PrimaryMuscleGroup.CORE,
            setOf(Equipment.BODYWEIGHT),
            true,
            ExerciseCautionTag.LOWER_BACK
        ),
        entry(
            ExerciseCatalogIds.BAND_PULL_APARTS,
            "Band Pull-aparts",
            PrimaryMuscleGroup.SHOULDERS,
            setOf(Equipment.RESISTANCE_BAND),
            true,
            ExerciseCautionTag.SHOULDER
        ),
        entry(
            ExerciseCatalogIds.WALL_SLIDES,
            "Wall Slides",
            PrimaryMuscleGroup.SHOULDERS,
            setOf(Equipment.WALL),
            true,
            ExerciseCautionTag.SHOULDER
        ),
        entry(
            ExerciseCatalogIds.LIGHT_DUMBBELL_PRESS,
            "Light Dumbbell Press",
            PrimaryMuscleGroup.SHOULDERS,
            setOf(Equipment.DUMBBELL),
            true,
            ExerciseCautionTag.SHOULDER,
            ExerciseCautionTag.OVERHEAD
        ),
        entry(
            ExerciseCatalogIds.GOBLET_SQUATS,
            "Goblet Squats",
            PrimaryMuscleGroup.QUADRICEPS,
            setOf(Equipment.DUMBBELL),
            true,
            ExerciseCautionTag.KNEE
        ),
        entry(
            ExerciseCatalogIds.GLUTE_BRIDGES,
            "Glute Bridges",
            PrimaryMuscleGroup.GLUTES,
            setOf(Equipment.BODYWEIGHT),
            true
        ),
    )
