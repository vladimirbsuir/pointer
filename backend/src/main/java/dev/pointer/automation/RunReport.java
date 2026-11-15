package dev.pointer.automation;

import java.math.RoundingMode;

final class RunReport {

    private static final int MAX_QUOTE_LENGTH = 4000;
    private static final int MAX_BODY_OUTPUT_LENGTH = 6000;
    private static final String DEFAULT_SUMMARY = "Changes made by the Pointer agent.";

    private RunReport() {}

    static String statusComment(AutomationRun run, Automation automation, String branchUrl, String agentAnswer) {
        var comment = new StringBuilder("### Pointer automation\n\n");
        comment.append("**Status:** ").append(statusLine(run, automation)).append('\n');
        if (branchUrl != null) {
            comment.append("\n**Branch:** ").append(branchUrl).append('\n');
        }
        if (run.getPullRequestUrl() != null) {
            comment.append("\n**Pull request:** ")
                    .append(run.getPullRequestUrl())
                    .append('\n');
        }
        if (AutomationRun.NEEDS_INPUT.equals(run.getFailureReason()) && agentAnswer != null) {
            comment.append("\nThe agent made no changes and replied:\n\n")
                    .append(quote(agentAnswer))
                    .append('\n');
        }
        if (run.getError() != null) {
            comment.append("\n").append(run.getError()).append('\n');
        }
        if (AutomationRun.CHECK_FAILED.equals(run.getFailureReason()) && run.getLastCheckOutput() != null) {
            comment.append("\n<details><summary>Output of <code>")
                    .append(automation.getCheckCommand())
                    .append("</code></summary>\n\n")
                    .append(codeBlock(run.getLastCheckOutput()))
                    .append("\n</details>\n");
        }
        return comment.append("\n<sub>Run ")
                .append(run.getId())
                .append("</sub>")
                .toString();
    }

    static String pullRequestBody(AutomationRun run, Automation automation, String agentAnswer, String failureReason) {
        var draft = failureReason != null;
        var body =
                new StringBuilder(agentAnswer == null || agentAnswer.isBlank() ? DEFAULT_SUMMARY : agentAnswer.strip());
        body.append("\n\n---\n\n");
        if (draft) {
            body.append("Pointer opened this pull request as a draft because the run did not finish: ")
                    .append(failureText(failureReason, automation))
                    .append(".\n\n");
        }
        body.append("| Issue | Checks run | Check command | Cost |\n|---|---|---|---|\n");
        body.append("| #").append(run.getIssueNumber());
        body.append(" | ").append(run.getAttempts()).append(" of ").append(automation.getMaxAttempts());
        body.append(" | ").append(automation.hasCheck() ? "`" + automation.getCheckCommand() + "`" : "none");
        body.append(" | ").append(cost(run)).append(" |\n\n");
        if (draft
                && run.getLastCheckOutput() != null
                && !run.getLastCheckOutput().isBlank()) {
            body.append("<details><summary>Last check output</summary>\n\n")
                    .append(codeBlock(run.getLastCheckOutput()))
                    .append("\n</details>\n\n");
        }
        return body.append(draft ? "Refs #" : "Closes #")
                .append(run.getIssueNumber())
                .toString();
    }

    private static String statusLine(AutomationRun run, Automation automation) {
        var attempts = run.getAttempts() + " of " + automation.getMaxAttempts();
        return switch (run.getStatus()) {
            case AutomationRun.QUEUED -> "queued, waiting for a free runner";
            case AutomationRun.RUNNING ->
                run.getAttempts() == 0
                        ? "the agent is working on the issue"
                        : "check " + attempts + " failed, the agent is fixing it";
            case AutomationRun.CHECKING ->
                "running the check command (check " + (run.getAttempts() + 1) + " of " + automation.getMaxAttempts()
                        + ")";
            case AutomationRun.SUCCEEDED -> "done, the pull request is ready for review";
            case AutomationRun.CANCELLED -> "cancelled";
            default -> "failed: " + failureText(run.getFailureReason(), automation);
        };
    }

    private static String failureText(String reason, Automation automation) {
        if (reason == null) {
            return "unknown reason";
        }
        return switch (reason) {
            case AutomationRun.NEEDS_INPUT -> "the agent needs more information";
            case AutomationRun.CHECK_FAILED ->
                "the check command still fails after " + automation.getMaxAttempts() + " checks";
            case AutomationRun.BUDGET_EXCEEDED -> "the budget of the run is used up";
            case AutomationRun.AGENT_FAILED -> "the agent stopped with an error";
            case AutomationRun.WORKSPACE_FAILED -> "the server workspace could not be prepared";
            case AutomationRun.PULL_REQUEST_FAILED -> "the pull request could not be opened";
            case AutomationRun.ERROR -> "an unexpected error stopped the run";
            default -> "the run could not be started";
        };
    }

    private static String cost(AutomationRun run) {
        return "$" + run.getCostUsd().setScale(4, RoundingMode.HALF_UP).toPlainString();
    }

    private static String quote(String text) {
        return IssuePrompt.truncate(text.strip(), MAX_QUOTE_LENGTH)
                .lines()
                .map(line -> "> " + line)
                .reduce((first, second) -> first + "\n" + second)
                .orElse("");
    }

    private static String codeBlock(String output) {
        var text = output.length() > MAX_BODY_OUTPUT_LENGTH
                ? output.substring(output.length() - MAX_BODY_OUTPUT_LENGTH)
                : output;
        return "```\n" + text.replace("```", "'''") + "\n```";
    }
}
