package com.example.ironpath.data.ai

import com.example.ironpath.domain.planner.OnDeviceExerciseProposal
import com.example.ironpath.domain.planner.OnDevicePlanProposal
import com.example.ironpath.domain.planner.OnDeviceWorkoutProposal
import com.example.ironpath.domain.planner.PlanDraftTextLimits
import com.example.ironpath.domain.planner.PlanValidationLimits
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** One app-owned structural contract shared by every debug remote provider. */
internal object RemotePlanJsonCodec {
    fun parse(outputText: String): OnDevicePlanProposal =
        Json.parseToJsonElement(outputText).jsonObject.toProposal()

    fun responseSchema() = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put("rationale", nullableStringSchema())
                put("warnings", arraySchema(stringSchema()))
                put("workouts", arraySchema(workoutSchema()))
            },
        )
        put("required", stringArray("rationale", "warnings", "workouts"))
        put("additionalProperties", false)
    }

    private fun workoutSchema() = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put("dayOfWeek", integerSchema())
                put("title", stringSchema())
                put("exercises", arraySchema(exerciseSchema()))
            },
        )
        put("required", stringArray("dayOfWeek", "title", "exercises"))
        put("additionalProperties", false)
    }

    private fun exerciseSchema() = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put("catalogId", stringSchema())
                put("sets", integerSchema())
                put("reps", integerSchema())
                put("targetWeightKg", numberSchema())
            },
        )
        put("required", stringArray("catalogId", "sets", "reps", "targetWeightKg"))
        put("additionalProperties", false)
    }

    private fun JsonObject.toProposal(): OnDevicePlanProposal {
        val warnings = array("warnings")
        check(warnings.size <= PlanDraftTextLimits.MAX_WARNING_COUNT)
        val workouts = array("workouts")
        check(
            workouts.size in
                PlanValidationLimits.MIN_TRAINING_DAYS..PlanValidationLimits.MAX_TRAINING_DAYS
        )
        return OnDevicePlanProposal(
            rationale =
                getValue("rationale").let {
                    if (it == JsonNull) null else it.jsonPrimitive.requireString()
                },
            warnings = warnings.map { it.jsonPrimitive.requireString() },
            workouts =
                workouts.map { workoutElement ->
                    val workout = workoutElement.jsonObject
                    val exercises = workout.array("exercises")
                    check(
                        exercises.size in
                            PlanValidationLimits.MIN_EXERCISES_PER_DAY..PlanValidationLimits
                                    .MAX_EXERCISES_PER_DAY
                    )
                    OnDeviceWorkoutProposal(
                        dayOfWeek = workout.int("dayOfWeek"),
                        title = workout.string("title"),
                        exercises =
                            exercises.map { exerciseElement ->
                                val exercise = exerciseElement.jsonObject
                                OnDeviceExerciseProposal(
                                    catalogId = exercise.string("catalogId"),
                                    sets = exercise.int("sets"),
                                    reps = exercise.int("reps"),
                                    targetWeightKg = exercise.double("targetWeightKg"),
                                )
                            },
                    )
                },
        )
    }

    private fun stringSchema() = buildJsonObject { put("type", "string") }

    private fun nullableStringSchema() = buildJsonObject {
        put(
            "type",
            buildJsonArray {
                add(JsonPrimitive("string"))
                add(JsonPrimitive("null"))
            },
        )
    }

    private fun integerSchema() = buildJsonObject { put("type", "integer") }

    private fun numberSchema() = buildJsonObject { put("type", "number") }

    private fun arraySchema(items: JsonObject) = buildJsonObject {
        put("type", "array")
        put("items", items)
    }

    private fun stringArray(vararg values: String) = buildJsonArray {
        values.forEach { add(JsonPrimitive(it)) }
    }

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.requireString()

    private fun JsonObject.int(key: String): Int =
        getValue(key).jsonPrimitive.let {
            check(!it.isString)
            checkNotNull(it.intOrNull)
        }

    private fun JsonObject.double(key: String): Double =
        getValue(key).jsonPrimitive.let {
            check(!it.isString)
            checkNotNull(it.doubleOrNull).also { value -> check(value.isFinite()) }
        }

    private fun JsonObject.array(key: String): JsonArray = getValue(key).jsonArray

    private fun JsonPrimitive.requireString(): String {
        check(isString)
        return content
    }
}
