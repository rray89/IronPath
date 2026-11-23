package com.example.ironpath.domain.planner

enum class RemoteFailureKind(val guidance: String) {
    CONFIGURATION("Select a provider and enter its API key."),
    AUTHENTICATION("The provider rejected the credentials. Check the API key."),
    ACCESS("The provider denied access. Check model and account permissions."),
    RATE_LIMIT("The provider rate-limited the request. Wait before retrying."),
    TIMEOUT("The request timed out. The provider may already have processed it."),
    NETWORK("The connection failed. Check the network before retrying."),
    SERVICE("The provider is temporarily unavailable."),
    REQUEST("The provider rejected the request. Check the selected model and API key."),
    BILLING("The provider requires available credit. Check the provider account before retrying."),
    INTERNAL("IronPath could not finish processing this request. You can choose a local plan."),
    RESPONSE_FORMAT("The response could not be read as a complete plan."),
    MAPPING("IronPath could not map the response to a workout."),
    VALIDATION("The returned workout failed IronPath's plan checks."),
}
