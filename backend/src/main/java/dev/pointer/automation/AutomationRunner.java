package dev.pointer.automation;

import dev.pointer.agent.AgentRunner;
import dev.pointer.common.error.ApiException;
import dev.pointer.container.GitHubRepositories;
import dev.pointer.container.RemoteDtos;
import dev.pointer.container.RemoteSessionService;
import dev.pointer.execution.ExecutionResult;
import dev.pointer.project.ProjectService;
import dev.pointer.provider.llm.Cancellation;
import dev.pointer.session.AgentSession;
import dev.pointer.session.Item;
import dev.pointer.session.SessionDtos;
import dev.pointer.session.SessionEventListener;
import dev.pointer.session.SessionEventRecord;
import dev.pointer.session.SessionService;
import dev.pointer.session.SessionStatus;
import dev.pointer.session.Turn;
import dev.pointer.user.UserService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class AutomationRunner implements SessionEventListener {

    static final Duration CHECK_TIMEOUT = Duration.ofMinutes(15);
    static final String BRANCH_PREFIX = "pointer/issue-";

    private static final Logger log = LoggerFactory.getLogger(AutomationRunner.class);
    private static final int MAX_SESSION_TITLE = 200;
    private static final int MAX_SLUG = 40;
    private static final String AGENT_MODE = "agent";
    private static final String WORKSPACE_QUEUED = "queued";
    private static final String WORKSPACE_FAILED = "failed";

    private final AutomationRepository automations;
    private final AutomationRunRepository runs;
    private final AutomationRuns store;
    private final ProjectService projects;
    private final UserService users;
    private final GitHubRepositories gitHub;
    private final RemoteSessionService remote;
    private final SessionService sessions;
    private final AgentRunner agent;
    private final AutomationProperties properties;
    private final Clock clock;

    private final Map<UUID, Object> locks = new ConcurrentHashMap<>();
    private final Map<UUID, Cancellation> checks = new ConcurrentHashMap<>();
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Instant> idleSince = new ConcurrentHashMap<>();

    void launch(UUID runId) {
        handle("automation-launch-", runId, this::begin);
    }

    void cancel(UUID runId) {
        var cancelled = store.transition(runId, run -> run.cancel(clock.instant()));
        if (cancelled.isEmpty()) {
            throw ApiException.conflict("run_not_active", "The run has already finished");
        }
        var run = cancelled.get();
        Optional.ofNullable(checks.get(runId)).ifPresent(Cancellation::cancel);
        if (run.getSessionId() != null) {
            var context = context(run);
            agent.cancel(context.userId(), run.getSessionId());
            Thread.ofVirtual().name("automation-cancel-" + runId).start(() -> updateStatusComment(run.getId()));
        }
    }

    @Override
    public void onSessionEvent(UUID userId, SessionEventRecord record) {
        if (!"turn.completed".equals(record.event().get("kind"))) {
            return;
        }
        var turnId = record.event().get("turnId");
        var finished = new FinishedTurn(
                turnId == null ? null : UUID.fromString(String.valueOf(turnId)),
                String.valueOf(record.event().get("reason")),
                errorMessage(record.event().get("error")));
        Thread.ofVirtual()
                .name("automation-event-" + record.sessionId())
                .start(() -> runs.findFirstBySessionIdAndStatusIn(record.sessionId(), AutomationRun.ACTIVE)
                        .ifPresent(
                                run -> handle("automation-turn-", run.getId(), active -> afterTurn(active, finished))));
    }

    @EventListener
    void onSessionDeleted(SessionService.SessionDeleted deleted) {
        runs.findFirstBySessionIdAndStatusIn(deleted.sessionId(), AutomationRun.ACTIVE)
                .ifPresent(run -> {
                    run.cancel(clock.instant());
                    runs.save(run);
                    Optional.ofNullable(checks.get(run.getId())).ifPresent(Cancellation::cancel);
                });
    }

    @EventListener(ApplicationReadyEvent.class)
    void recoverRuns() {
        for (var run : runs.findByStatusIn(AutomationRun.ACTIVE)) {
            if (run.getSessionId() == null) {
                launch(run.getId());
            } else if (AutomationRun.CHECKING.equals(run.getStatus())) {
                handle("automation-recheck-", run.getId(), active -> check(active, context(active)));
            }
        }
    }

    @Scheduled(fixedDelayString = "${pointer.automation.watch-interval:3s}")
    void watch() {
        for (var run : runs.findByStatusIn(List.of(AutomationRun.QUEUED, AutomationRun.RUNNING))) {
            try {
                watch(run);
            } catch (RuntimeException exception) {
                log.warn("Watching automation run {} failed: {}", run.getId(), exception.getMessage());
            }
        }
    }

    private void watch(AutomationRun run) {
        var runId = run.getId();
        if (run.getSessionId() == null || inFlight.contains(runId)) {
            idleSince.remove(runId);
            return;
        }
        var context = context(run);
        var workspace = remote.forSession(context.userId(), run.getSessionId()).orElse(null);
        if (workspace == null || WORKSPACE_FAILED.equals(workspace.status())) {
            var error = workspace == null ? "The server workspace is gone" : workspace.error();
            handle("automation-workspace-", runId, active -> {
                finishFailed(active, context, AutomationRun.WORKSPACE_FAILED, error);
                agent.cancel(context.userId(), active.getSessionId());
            });
            return;
        }
        if (AutomationRun.QUEUED.equals(run.getStatus()) && !WORKSPACE_QUEUED.equals(workspace.status())) {
            store.transition(runId, active -> active.changeStatus(AutomationRun.RUNNING));
            updateStatusComment(runId);
        }
        if (!isSessionIdle(context.userId(), run.getSessionId())) {
            idleSince.remove(runId);
            return;
        }
        var since = idleSince.computeIfAbsent(runId, id -> clock.instant());
        if (Duration.between(since, clock.instant()).compareTo(properties.stallGrace()) >= 0) {
            idleSince.remove(runId);
            handle("automation-resume-", runId, active -> resume(active, context(active)));
        }
    }

    private void handle(String threadName, UUID runId, Consumer<AutomationRun> step) {
        inFlight.add(runId);
        Thread.ofVirtual().name(threadName + runId).start(() -> {
            synchronized (locks.computeIfAbsent(runId, id -> new Object())) {
                inFlight.add(runId);
                try {
                    store.active(runId).ifPresent(step);
                } catch (RuntimeException exception) {
                    log.error("Automation run {} failed unexpectedly", runId, exception);
                    failAfterError(runId, exception);
                } finally {
                    inFlight.remove(runId);
                }
            }
        });
    }

    private void failAfterError(UUID runId, RuntimeException exception) {
        var message = exception instanceof ApiException ? exception.getMessage() : "Unexpected error in the run";
        store.transition(
                runId,
                run -> run.fail(
                        run.getSessionId() == null ? AutomationRun.START_FAILED : AutomationRun.ERROR,
                        message,
                        clock.instant()));
        updateStatusComment(runId);
    }

    private void begin(AutomationRun run) {
        if (run.getSessionId() != null) {
            return;
        }
        var context = context(run);
        var automation = context.automation();
        var issue = gitHub.issue(context.token(), context.repository(), run.getIssueNumber());
        var created = remote.create(
                context.userId(),
                automation.getProjectId(),
                new RemoteDtos.CreateRemoteSessionRequest(
                        sessionTitle(issue), AGENT_MODE, automation.getModelId(), automation.getBaseBranch()),
                branchName(run, issue.title()));
        var sessionId = created.session().id();
        if (automation.getBudgetUsd() != null || automation.isEscalation()) {
            sessions.update(
                    context.userId(),
                    sessionId,
                    new SessionDtos.UpdateSessionRequest(
                            null,
                            null,
                            null,
                            null,
                            null,
                            automation.getBudgetUsd(),
                            automation.isEscalation() ? AgentSession.ROUTING_SMART : null,
                            null,
                            automation.isEscalation() ? AgentSession.ESCALATION_ON : null));
        }
        var attached = store.transition(run.getId(), active -> {
            active.attachSession(sessionId);
            active.retitle(issue.title());
        });
        if (attached.isEmpty()) {
            return;
        }
        postStatusComment(attached.get(), context);
        agent.send(
                context.userId(),
                sessionId,
                new SessionDtos.SendMessageRequest(prompt(issue, context), null, null, null),
                "automation-" + run.getId());
    }

    private void afterTurn(AutomationRun run, FinishedTurn turn) {
        var context = context(run);
        switch (turn.reason()) {
            case Turn.COMPLETED -> verify(run, context, turn.turnId());
            case Turn.CANCELLED -> {
                store.transition(run.getId(), active -> {
                    active.recordCost(cost(context, active));
                    active.cancel(clock.instant());
                });
                updateStatusComment(run.getId());
            }
            case Turn.INTERRUPTED -> resume(run, context);
            case Turn.BUDGET_EXCEEDED -> finishFailed(run, context, AutomationRun.BUDGET_EXCEEDED, turn.error());
            default -> finishFailed(run, context, AutomationRun.AGENT_FAILED, turn.error());
        }
    }

    private void verify(AutomationRun run, RunContext context, UUID turnId) {
        if (!isSessionIdle(context.userId(), run.getSessionId())) {
            return;
        }
        var workspace =
                remote.publishNow(context.userId(), run.getSessionId(), turnId).orElse(null);
        if (workspace == null || workspace.pushedCommit() == null) {
            var pushError = workspace == null ? null : workspace.error();
            finishFailed(
                    run,
                    context,
                    pushError == null ? AutomationRun.NEEDS_INPUT : AutomationRun.WORKSPACE_FAILED,
                    pushError);
            return;
        }
        if (context.automation().hasCheck()) {
            check(run, context);
        } else {
            store.transition(run.getId(), active -> active.checkFinished(null));
            succeed(run, context);
        }
    }

    private void check(AutomationRun run, RunContext context) {
        var runId = run.getId();
        var automation = context.automation();
        if (store.transition(runId, active -> active.changeStatus(AutomationRun.CHECKING))
                .isEmpty()) {
            return;
        }
        updateStatusComment(runId);
        var cancellation = new Cancellation();
        checks.put(runId, cancellation);
        ExecutionResult result;
        try {
            result = remote.runCommand(run.getSessionId(), automation.getCheckCommand(), CHECK_TIMEOUT, cancellation);
        } finally {
            checks.remove(runId);
        }
        var outcome = CheckOutcome.of(result);
        var checked = store.transition(runId, active -> {
            active.checkFinished(outcome.output());
            active.recordCost(cost(context, active));
            if (!outcome.passed() && active.getAttempts() < automation.getMaxAttempts()) {
                active.changeStatus(AutomationRun.RUNNING);
            }
        });
        if (checked.isEmpty()) {
            return;
        }
        if (outcome.passed()) {
            succeed(checked.get(), context);
        } else if (checked.get().getAttempts() >= automation.getMaxAttempts()) {
            finishFailed(checked.get(), context, AutomationRun.CHECK_FAILED, null);
        } else {
            updateStatusComment(runId);
            send(
                    run,
                    context,
                    IssuePrompt.checkFailed(
                            run.getIssueNumber(),
                            automation.getCheckCommand(),
                            outcome.description(),
                            outcome.output()),
                    Item.ORIGIN_CHECK_FAILED);
        }
    }

    private void succeed(AutomationRun run, RunContext context) {
        String url;
        try {
            url = openPullRequest(run, context, null);
        } catch (ApiException exception) {
            store.transition(
                    run.getId(),
                    active -> active.fail(AutomationRun.PULL_REQUEST_FAILED, exception.getMessage(), clock.instant()));
            updateStatusComment(run.getId());
            return;
        }
        store.transition(run.getId(), active -> {
            active.pullRequestOpened(url);
            active.recordCost(cost(context, active));
            active.succeed(clock.instant());
        });
        updateStatusComment(run.getId());
    }

    private void finishFailed(AutomationRun run, RunContext context, String reason, String error) {
        var runId = run.getId();
        if (store.active(runId).isEmpty()) {
            return;
        }
        var draftUrl = openDraft(run, context, reason);
        store.transition(runId, active -> {
            active.recordCost(cost(context, active));
            draftUrl.ifPresent(active::pullRequestOpened);
            active.fail(reason, error, clock.instant());
        });
        updateStatusComment(runId);
    }

    private Optional<String> openDraft(AutomationRun run, RunContext context, String reason) {
        try {
            return hasPushedChanges(context, run)
                    ? Optional.ofNullable(openPullRequest(run, context, reason))
                    : Optional.empty();
        } catch (ApiException exception) {
            log.warn("Could not open a draft pull request for run {}: {}", run.getId(), exception.getMessage());
            return Optional.empty();
        }
    }

    private void resume(AutomationRun run, RunContext context) {
        if (!isSessionIdle(context.userId(), run.getSessionId())) {
            return;
        }
        if (AutomationRun.QUEUED.equals(run.getStatus())) {
            store.transition(run.getId(), active -> active.changeStatus(AutomationRun.RUNNING));
        }
        var started = !sessions.items(context.userId(), run.getSessionId()).isEmpty();
        var text = started
                ? IssuePrompt.resume(run.getIssueNumber())
                : prompt(gitHub.issue(context.token(), context.repository(), run.getIssueNumber()), context);
        send(run, context, text, null);
    }

    private void send(AutomationRun run, RunContext context, String text, String origin) {
        try {
            agent.send(
                    context.userId(),
                    run.getSessionId(),
                    new SessionDtos.SendMessageRequest(text, null, null, null),
                    null,
                    origin);
        } catch (ApiException exception) {
            finishFailed(run, context, AutomationRun.AGENT_FAILED, exception.getMessage());
        }
    }

    private String prompt(GitHubRepositories.Issue issue, RunContext context) {
        var comments =
                gitHub.issueComments(context.token(), context.repository(), issue.number(), IssuePrompt.MAX_COMMENTS);
        return IssuePrompt.build(issue, comments);
    }

    private String openPullRequest(AutomationRun run, RunContext context, String failureReason) {
        var current = runs.findById(run.getId()).orElse(run);
        var body = RunReport.pullRequestBody(
                current, context.automation(), lastAnswer(context.userId(), run.getSessionId()), failureReason);
        return remote.openPullRequest(
                        context.userId(),
                        run.getSessionId(),
                        new RemoteSessionService.PullRequestDraft(run.getIssueTitle(), body, failureReason != null))
                .url();
    }

    private boolean hasPushedChanges(RunContext context, AutomationRun run) {
        if (run.getSessionId() == null) {
            return false;
        }
        return remote.forSession(context.userId(), run.getSessionId())
                .map(workspace -> workspace.pushedCommit() != null)
                .orElse(false);
    }

    private void postStatusComment(AutomationRun run, RunContext context) {
        try {
            var commentId = gitHub.createIssueComment(
                    context.token(),
                    context.repository(),
                    run.getIssueNumber(),
                    RunReport.statusComment(run, context.automation(), null, null));
            store.record(run.getId(), active -> active.statusCommentPosted(commentId));
        } catch (RuntimeException exception) {
            log.warn("Could not comment on issue #{}: {}", run.getIssueNumber(), exception.getMessage());
        }
    }

    private void updateStatusComment(UUID runId) {
        try {
            var run = runs.findById(runId).orElse(null);
            if (run == null) {
                return;
            }
            var context = context(run);
            var branchUrl = run.getSessionId() == null
                    ? null
                    : remote.forSession(context.userId(), run.getSessionId())
                            .map(RemoteDtos.RemoteWorkspaceResponse::branchUrl)
                            .orElse(null);
            var answer = AutomationRun.NEEDS_INPUT.equals(run.getFailureReason())
                    ? lastAnswer(context.userId(), run.getSessionId())
                    : null;
            var body = RunReport.statusComment(run, context.automation(), branchUrl, answer);
            if (run.getStatusCommentId() == null) {
                var commentId =
                        gitHub.createIssueComment(context.token(), context.repository(), run.getIssueNumber(), body);
                store.record(runId, current -> current.statusCommentPosted(commentId));
            } else {
                gitHub.updateIssueComment(context.token(), context.repository(), run.getStatusCommentId(), body);
            }
        } catch (RuntimeException exception) {
            log.warn("Could not update the status comment of run {}: {}", runId, exception.getMessage());
        }
    }

    private String lastAnswer(UUID userId, UUID sessionId) {
        if (sessionId == null) {
            return null;
        }
        var items = sessions.items(userId, sessionId);
        for (var index = items.size() - 1; index >= 0; index--) {
            var item = items.get(index);
            if ("assistant_message".equals(item.get("type"))
                    && item.get("text") instanceof String answer
                    && !answer.isBlank()) {
                return answer;
            }
        }
        return null;
    }

    private BigDecimal cost(RunContext context, AutomationRun run) {
        if (run.getSessionId() == null) {
            return null;
        }
        try {
            return sessions.get(context.userId(), run.getSessionId()).estimatedCost();
        } catch (ApiException exception) {
            return null;
        }
    }

    private boolean isSessionIdle(UUID userId, UUID sessionId) {
        var status = sessions.get(userId, sessionId).status();
        return SessionStatus.IDLE.equals(status) || SessionStatus.ERROR.equals(status);
    }

    private RunContext context(AutomationRun run) {
        var automation = automations.findById(run.getAutomationId()).orElseThrow();
        var project = projects.owned(automation.getUserId(), automation.getProjectId());
        var credentials = users.gitHubCredentials(automation.getUserId())
                .orElseThrow(() -> ApiException.badRequest(
                        "github_repository_access_required",
                        "Allow Pointer to access your GitHub repositories in settings"));
        if (project.getRepository() == null) {
            throw ApiException.badRequest(
                    "project_repository_missing", "Set the GitHub repository of the project first");
        }
        return new RunContext(automation, project.getRepository(), credentials.accessToken());
    }

    private String branchName(AutomationRun run, String issueTitle) {
        var slug = issueTitle
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (slug.length() > MAX_SLUG) {
            slug = slug.substring(0, MAX_SLUG).replaceAll("-+$", "");
        }
        var branch = BRANCH_PREFIX + run.getIssueNumber() + (slug.isEmpty() ? "" : "-" + slug);
        var earlierRuns = runs.countByAutomationIdAndIssueNumber(run.getAutomationId(), run.getIssueNumber());
        return earlierRuns > 1 ? branch + "-" + earlierRuns : branch;
    }

    private static String sessionTitle(GitHubRepositories.Issue issue) {
        var title = "#" + issue.number() + " " + issue.title().strip();
        return title.length() > MAX_SESSION_TITLE ? title.substring(0, MAX_SESSION_TITLE) : title;
    }

    private static String errorMessage(Object error) {
        return error instanceof Map<?, ?> map && map.get("message") != null ? String.valueOf(map.get("message")) : null;
    }

    private record FinishedTurn(UUID turnId, String reason, String error) {}

    private record RunContext(Automation automation, String repository, String token) {

        UUID userId() {
            return automation.getUserId();
        }
    }

    private record CheckOutcome(boolean passed, String description, String output) {

        static CheckOutcome of(ExecutionResult result) {
            if (!result.succeeded()) {
                var message = result.error() == null
                        ? result.status()
                        : String.valueOf(result.error().getOrDefault("message", result.status()));
                var description = ExecutionResult.TIMEOUT.equals(result.status()) ? "a timeout" : "an error";
                return new CheckOutcome(false, description, IssuePrompt.tail(message));
            }
            var exitCode = result.result().get("exitCode") instanceof Number number ? number.intValue() : -1;
            var output = IssuePrompt.tail(String.valueOf(result.result().getOrDefault("output", "")));
            return new CheckOutcome(exitCode == 0, "exit code " + exitCode, output);
        }
    }
}
