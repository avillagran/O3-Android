package dev.avillagran.ot3android.core.settings

/** P03 boundary; source-control descriptors are exported by the common transport for P05. */
object GeneralSettingsFields {
    val keys: Set<String> = setOf(
        "defaultModelSelection", "defaultRuntimeMode", "defaultThreadEnvMode", "worktreeSubmodules",
        "autoResumeLimitedThreads", "snoozeLimitedThreads", "sidebarAutoSettleOnMerge", "sidebarAutoSettleAfterDays",
        "sidebarAutoSettleKeepPullRequests", "responseStreamingMode", "idleCompaction", "enableProviderUpdateChecks",
        "continueThreadsAfterServerUpdate", "backgroundActivityProfile", "guestSharingEnabled", "newWorktreesStartFromOrigin",
        "textGenerationModelSelection", "threadTitleInstructions", "observability.otlpTracesUrl",
        "observability.otlpMetricsUrl", "observability.otlpLogsUrl",
    )
}
