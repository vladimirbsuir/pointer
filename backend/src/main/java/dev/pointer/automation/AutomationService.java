package dev.pointer.automation;

import dev.pointer.common.crypto.SecretCipher;
import dev.pointer.common.crypto.Tokens;
import dev.pointer.common.error.ApiException;
import dev.pointer.container.GitHubRepositories;
import dev.pointer.container.RemoteSessionService;
import dev.pointer.project.Project;
import dev.pointer.project.ProjectService;
import dev.pointer.provider.ProviderService;
import dev.pointer.user.UserService;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;

@Service
public class AutomationService {

    static final String WEBHOOK_PATH = "/api/v1/webhooks/github/";

    private static final Logger log = LoggerFactory.getLogger(AutomationService.class);
    private static final int WEBHOOK_SECRET_BYTES = 32;
    private static final int RUN_LIST_LIMIT = 50;
    private static final List<String> WEBHOOK_EVENTS = List.of("issues");

    private final AutomationRepository automations;
    private final AutomationRunRepository runs;
    private final AutomationRunner runner;
    private final ProjectService projects;
    private final ProviderService providers;
    private final UserService users;
    private final GitHubRepositories gitHub;
    private final RemoteSessionService remote;
    private final SecretCipher cipher;
    private final Clock clock;
    private final String publicUrl;

    AutomationService(
            AutomationRepository automations,
            AutomationRunRepository runs,
            AutomationRunner runner,
            ProjectService projects,
            ProviderService providers,
            UserService users,
            GitHubRepositories gitHub,
            RemoteSessionService remote,
            SecretCipher cipher,
            Clock clock,
            @Value("${pointer.public-url:}") String publicUrl) {
        this.automations = automations;
        this.runs = runs;
        this.runner = runner;
        this.projects = projects;
        this.providers = providers;
        this.users = users;
        this.gitHub = gitHub;
        this.remote = remote;
        this.cipher = cipher;
        this.clock = clock;
        this.publicUrl = publicUrl == null ? "" : publicUrl.strip().replaceAll("/+$", "");
    }

    AutomationDtos.AutomationResponse get(UUID userId, UUID projectId) {
        projects.owned(userId, projectId);
        return automations
                .findByProjectId(projectId)
                .map(automation -> AutomationDtos.AutomationResponse.of(automation, webhookState(automation), null))
                .orElseGet(() -> AutomationDtos.AutomationResponse.notConfigured(webhookState(null)));
    }

    AutomationDtos.AutomationResponse save(UUID userId, UUID projectId, AutomationDtos.SaveAutomationRequest request) {
        var project = projects.owned(userId, projectId);
        var repository = repository(project);
        var token = token(userId);
        if (request.modelId() != null && !providers.modelBelongsTo(userId, request.modelId())) {
            throw ApiException.notFound("model_not_found", "Model does not exist");
        }
        var now = clock.instant();
        var automation = automations
                .findByProjectId(projectId)
                .orElseGet(() ->
                        new Automation(userId, projectId, cipher.encrypt(Tokens.random(WEBHOOK_SECRET_BYTES)), now));
        automation.configure(
                new Automation.Settings(
                        request.enabled(),
                        request.triggerLabel() == null
                                ? automation.getTriggerLabel()
                                : request.triggerLabel().strip(),
                        blankToNull(request.baseBranch()),
                        request.modelId(),
                        blankToNull(request.checkCommand()),
                        request.maxAttempts() == null ? automation.getMaxAttempts() : request.maxAttempts(),
                        positiveOrNull(request.budgetUsd())),
                now);
        var saved = automations.save(automation);
        var webhookError = syncWebhook(saved, repository, token);
        return AutomationDtos.AutomationResponse.of(automations.save(saved), webhookState(saved), webhookError);
    }

    void delete(UUID userId, UUID projectId) {
        var project = projects.owned(userId, projectId);
        automations.findByProjectId(projectId).ifPresent(automation -> {
            runs.findByStatusIn(AutomationRun.ACTIVE).stream()
                    .filter(run -> run.getAutomationId().equals(automation.getId()))
                    .forEach(run -> cancelQuietly(run.getId()));
            if (automation.getWebhookId() != null && project.getRepository() != null) {
                users.gitHubCredentials(userId)
                        .ifPresent(credentials ->
                                removeWebhook(automation, project.getRepository(), credentials.accessToken()));
            }
            automations.delete(automation);
        });
    }

    public AutomationDtos.RunResponse startManual(UUID userId, UUID projectId, int issueNumber, String trigger) {
        var project = projects.owned(userId, projectId);
        var automation = automations
                .findByProjectId(projectId)
                .orElseThrow(() -> ApiException.notFound(
                        "automation_not_configured", "Set up the automation of the project first"));
        ensureRunnable(automation);
        var repository = repository(project);
        var issue = gitHub.issue(token(userId), repository, issueNumber);
        if (issue.isPullRequest()) {
            throw ApiException.badRequest("not_an_issue", "#" + issueNumber + " is a pull request, not an issue");
        }
        var run = createRun(automation, issueNumber, issue.title(), trigger)
                .orElseThrow(() -> ApiException.conflict(
                        "run_already_active", "A run for issue #" + issueNumber + " is already in progress"));
        runner.launch(run.getId());
        return AutomationDtos.RunResponse.of(run, automation, repository);
    }

    Optional<UUID> startFromLabel(Automation automation, int issueNumber, String issueTitle) {
        try {
            ensureRunnable(automation);
        } catch (ApiException exception) {
            log.warn("Label trigger for automation {} ignored: {}", automation.getId(), exception.getMessage());
            return Optional.empty();
        }
        var run = createRun(automation, issueNumber, issueTitle, AutomationRun.TRIGGER_LABEL);
        run.ifPresent(created -> runner.launch(created.getId()));
        return run.map(AutomationRun::getId);
    }

    List<AutomationDtos.RunResponse> runs(UUID userId, UUID projectId) {
        var project = projects.owned(userId, projectId);
        return automations
                .findByProjectId(projectId)
                .map(automation ->
                        runs
                                .findByAutomationIdOrderByStartedAtDesc(automation.getId(), Limit.of(RUN_LIST_LIMIT))
                                .stream()
                                .map(run -> AutomationDtos.RunResponse.of(run, automation, project.getRepository()))
                                .toList())
                .orElseGet(List::of);
    }

    AutomationDtos.RunResponse cancel(UUID userId, UUID projectId, UUID runId) {
        var project = projects.owned(userId, projectId);
        var automation = automations
                .findByProjectId(projectId)
                .orElseThrow(() -> ApiException.notFound("run_not_found", "Run does not exist"));
        runs.findByIdAndAutomationId(runId, automation.getId())
                .orElseThrow(() -> ApiException.notFound("run_not_found", "Run does not exist"));
        runner.cancel(runId);
        return AutomationDtos.RunResponse.of(runs.findById(runId).orElseThrow(), automation, project.getRepository());
    }

    public List<UUID> enabledProjects(UUID userId) {
        return automations.findByUserIdAndEnabledTrue(userId).stream()
                .map(Automation::getProjectId)
                .toList();
    }

    Optional<Automation> find(UUID automationId) {
        return automations.findById(automationId);
    }

    String webhookSecret(Automation automation) {
        return cipher.decrypt(automation.getWebhookSecretEncrypted());
    }

    private Optional<AutomationRun> createRun(Automation automation, int issueNumber, String title, String trigger) {
        if (runs.existsByAutomationIdAndIssueNumberAndStatusIn(automation.getId(), issueNumber, AutomationRun.ACTIVE)) {
            return Optional.empty();
        }
        try {
            return Optional.of(runs.saveAndFlush(
                    new AutomationRun(automation.getId(), issueNumber, title, trigger, clock.instant())));
        } catch (DataIntegrityViolationException exception) {
            return Optional.empty();
        }
    }

    private void ensureRunnable(Automation automation) {
        if (!automation.isEnabled()) {
            throw ApiException.conflict("automation_disabled", "The automation of the project is turned off");
        }
        if (!remote.isEnabled()) {
            throw ApiException.unavailable("remote_execution_disabled", "Remote execution is not configured");
        }
        token(automation.getUserId());
    }

    private String syncWebhook(Automation automation, String repository, String token) {
        try {
            if (!automation.isEnabled()) {
                removeWebhook(automation, repository, token);
            } else if (automation.getWebhookId() == null && !publicUrl.isEmpty()) {
                var hook = new GitHubRepositories.Hook(
                        publicUrl + WEBHOOK_PATH + automation.getId(), webhookSecret(automation), WEBHOOK_EVENTS);
                automation.webhookCreated(gitHub.createHook(token, repository, hook), clock.instant());
            }
            return null;
        } catch (ApiException exception) {
            return exception.getMessage();
        }
    }

    private void removeWebhook(Automation automation, String repository, String token) {
        if (automation.getWebhookId() == null) {
            return;
        }
        gitHub.deleteHook(token, repository, automation.getWebhookId());
        automation.webhookRemoved(clock.instant());
    }

    private void cancelQuietly(UUID runId) {
        try {
            runner.cancel(runId);
        } catch (ApiException exception) {
            log.debug("Run {} was not active: {}", runId, exception.getMessage());
        }
    }

    private String webhookState(Automation automation) {
        if (publicUrl.isEmpty()) {
            return AutomationDtos.WEBHOOK_NO_PUBLIC_URL;
        }
        return automation != null && automation.getWebhookId() != null
                ? AutomationDtos.WEBHOOK_CREATED
                : AutomationDtos.WEBHOOK_MISSING;
    }

    private String token(UUID userId) {
        return users.gitHubCredentials(userId)
                .orElseThrow(() -> ApiException.badRequest(
                        "github_repository_access_required",
                        "Allow Pointer to access your GitHub repositories in settings"))
                .accessToken();
    }

    private static String repository(Project project) {
        if (project.getRepository() == null) {
            throw ApiException.badRequest(
                    "project_repository_missing", "Set the GitHub repository of the project first");
        }
        return project.getRepository();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static BigDecimal positiveOrNull(BigDecimal value) {
        return value == null || value.signum() <= 0 ? null : value;
    }
}
