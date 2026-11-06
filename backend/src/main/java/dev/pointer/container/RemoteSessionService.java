package dev.pointer.container;

import dev.pointer.common.error.ApiException;
import dev.pointer.device.DeviceService;
import dev.pointer.execution.ExecutionListener;
import dev.pointer.execution.ExecutionRequest;
import dev.pointer.execution.ExecutionResult;
import dev.pointer.execution.ExecutionService;
import dev.pointer.project.ProjectService;
import dev.pointer.provider.llm.Cancellation;
import dev.pointer.session.SessionDtos;
import dev.pointer.session.SessionService;
import dev.pointer.session.SessionStatus;
import dev.pointer.user.UserService;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionTemplate;

public class RemoteSessionService {

    static final String PREPARE = "pointer.repo_prepare";
    static final String PUBLISH = "pointer.repo_publish";

    private static final Logger log = LoggerFactory.getLogger(RemoteSessionService.class);
    private static final Duration PUBLISH_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration FILE_TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_COMMIT_SUBJECT = 72;
    private static final String DEFAULT_COMMIT_MESSAGE = "Pointer session changes";
    private static final String BRANCH_PREFIX = "pointer/";
    private static final Duration SLOT_POLL_INTERVAL = Duration.ofSeconds(2);
    private static final List<String> RUNNING_STATUSES =
            List.of(RemoteWorkspace.STARTING, RemoteWorkspace.PREPARING, RemoteWorkspace.READY);

    private final ContainerProperties properties;
    private final ContainerRuntime runtime;
    private final RemoteWorkspaceRepository workspaces;
    private final ProjectService projects;
    private final SessionService sessions;
    private final DeviceService devices;
    private final UserService users;
    private final GitHubRepositories gitHub;
    private final ObjectProvider<ExecutionService> execution;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final Set<UUID> starting = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Object> publishLocks = new ConcurrentHashMap<>();
    private final Object slots = new Object();
    private final ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().name("remote-timeouts").factory());

    RemoteSessionService(
            ContainerProperties properties,
            ContainerRuntime runtime,
            RemoteWorkspaceRepository workspaces,
            ProjectService projects,
            SessionService sessions,
            DeviceService devices,
            UserService users,
            GitHubRepositories gitHub,
            ObjectProvider<ExecutionService> execution,
            TransactionTemplate transactions,
            Clock clock) {
        this.properties = properties;
        this.runtime = runtime;
        this.workspaces = workspaces;
        this.projects = projects;
        this.sessions = sessions;
        this.devices = devices;
        this.users = users;
        this.gitHub = gitHub;
        this.execution = execution;
        this.transactions = transactions;
        this.clock = clock;
    }

    public boolean isEnabled() {
        return properties.enabled();
    }

    RemoteDtos.RemoteStatus status(UUID userId) {
        return new RemoteDtos.RemoteStatus(
                properties.enabled(), users.gitHubCredentials(userId).isPresent());
    }

    public RemoteDtos.RemoteSessionResponse create(
            UUID userId, UUID projectId, RemoteDtos.CreateRemoteSessionRequest request) {
        ensureEnabled();
        var project = projects.owned(userId, projectId);
        if (project.getRepository() == null) {
            throw ApiException.badRequest(
                    "project_repository_missing", "Set the GitHub repository of the project first");
        }
        var credentials = credentials(userId);
        var repository = gitHub.repository(credentials.accessToken(), project.getRepository());
        var baseBranch = request.baseBranch() == null || request.baseBranch().isBlank()
                ? repository.defaultBranch()
                : request.baseBranch();
        var runner = devices.createRunner(userId, "Server · " + repository.fullName());
        var session = sessions.create(
                userId,
                runner.id(),
                projectId,
                new SessionDtos.CreateSessionRequest(request.title(), request.mode(), request.modelId()));
        var updated = sessions.update(
                userId, session.id(), new SessionDtos.UpdateSessionRequest(null, null, null, true, null));
        var workspace = workspaces.save(new RemoteWorkspace(
                session.id(),
                userId,
                runner.id(),
                repository.fullName(),
                BRANCH_PREFIX + session.id().toString().substring(0, 8),
                baseBranch,
                clock.instant()));
        startAsync(workspace.getId());
        return new RemoteDtos.RemoteSessionResponse(updated, toResponse(workspace));
    }

    public List<RemoteDtos.RemoteWorkspaceResponse> forProject(UUID userId, UUID projectId) {
        var sessionIds = sessions.list(userId, projectId).stream()
                .map(SessionDtos.SessionResponse::id)
                .toList();
        return workspaces.findByUserIdAndSessionIdIn(userId, sessionIds).stream()
                .map(RemoteSessionService::toResponse)
                .toList();
    }

    public Optional<RemoteDtos.RemoteWorkspaceResponse> forSession(UUID userId, UUID sessionId) {
        sessions.get(userId, sessionId);
        return workspaces.findBySessionId(sessionId).map(RemoteSessionService::toResponse);
    }

    public boolean isRemote(UUID sessionId) {
        return workspaces.findBySessionId(sessionId).isPresent();
    }

    RemoteDtos.PullRequestResponse openPullRequest(UUID userId, UUID sessionId) {
        var session = sessions.get(userId, sessionId);
        var workspace = owned(userId, sessionId);
        if (workspace.getPushedCommit() == null) {
            throw ApiException.conflict("nothing_pushed", "The session has not pushed any changes yet");
        }
        var credentials = credentials(userId);
        var title = session.title() == null || session.title().isBlank()
                ? "Pointer session " + workspace.getBranch()
                : session.title();
        var url = gitHub.openPullRequest(
                credentials.accessToken(),
                new GitHubRepositories.PullRequest(
                        workspace.getRepository(),
                        workspace.getBranch(),
                        workspace.getBaseBranch(),
                        title,
                        "Created by Pointer from session " + session.id() + "."));
        update(workspace.getId(), current -> {
            current.pullRequestOpened(url, clock.instant());
            return current;
        });
        return new RemoteDtos.PullRequestResponse(url);
    }

    List<RemoteDtos.RepositoryResponse> repositories(UUID userId) {
        return gitHub.list(credentials(userId).accessToken()).stream()
                .map(repository -> new RemoteDtos.RepositoryResponse(
                        repository.fullName(), repository.defaultBranch(), repository.isPrivate()))
                .toList();
    }

    RemoteDtos.RemoteFileSuggestions suggestFiles(UUID userId, UUID sessionId, String query) {
        var result = runFileOperation(userId, sessionId, "pointer.suggest_files", Map.of("query", query));
        var paths = result.get("paths") instanceof List<?> list
                ? list.stream().map(String::valueOf).toList()
                : List.<String>of();
        return new RemoteDtos.RemoteFileSuggestions(paths);
    }

    RemoteDtos.RemoteFile readFile(UUID userId, UUID sessionId, String path) {
        var result = runFileOperation(userId, sessionId, "pointer.read_attachment", Map.of("path", path));
        return new RemoteDtos.RemoteFile(path, String.valueOf(result.getOrDefault("content", "")));
    }

    boolean manages(UUID deviceId) {
        return properties.enabled() && workspaces.findByDeviceId(deviceId).isPresent();
    }

    boolean isReady(ExecutionRequest request) {
        if (request.operation().startsWith("pointer.repo_")) {
            return true;
        }
        return workspaces
                .findByDeviceId(request.deviceId())
                .map(RemoteWorkspace::isReady)
                .orElse(true);
    }

    void wake(ExecutionRequest request) {
        if (request.operation().startsWith("pointer.repo_")) {
            return;
        }
        workspaces.findByDeviceId(request.deviceId()).ifPresent(workspace -> startAsync(workspace.getId()));
    }

    void touch(UUID sessionId) {
        workspaces
                .findBySessionId(sessionId)
                .ifPresent(workspace -> update(workspace.getId(), current -> {
                    current.touch(clock.instant());
                    return current;
                }));
    }

    void onTurnCompleted(UUID userId, UUID sessionId, UUID turnId) {
        var workspace = workspaces.findBySessionId(sessionId).orElse(null);
        if (workspace == null || !workspace.isReady()) {
            return;
        }
        Thread.ofVirtual().name("remote-publish-" + sessionId).start(() -> publish(userId, sessionId, turnId));
    }

    void onSessionDeleted(UUID sessionId, UUID deviceId) {
        if (!devices.isRunner(deviceId)) {
            return;
        }
        Thread.ofVirtual().name("remote-remove-" + sessionId).start(() -> {
            try {
                runtime.remove(containerName(sessionId), volumeName(sessionId));
            } catch (ContainerException exception) {
                log.warn("Could not remove the container of session {}: {}", sessionId, exception.getMessage());
            }
            devices.revoke(deviceId);
        });
    }

    void stopIdle() {
        var cutoff = clock.instant().minus(properties.idleTimeout());
        for (var workspace : workspaces.findByStatusAndLastActiveAtBefore(RemoteWorkspace.READY, cutoff)) {
            if (!isSessionIdle(workspace)) {
                continue;
            }
            try {
                runtime.stop(workspace.getContainerName());
                changeStatus(workspace.getId(), RemoteWorkspace.STOPPED, null);
            } catch (ContainerException exception) {
                log.warn("Could not stop container {}: {}", workspace.getContainerName(), exception.getMessage());
            }
        }
    }

    void resetInterruptedStarts() {
        workspaces
                .findByStatusIn(List.of(RemoteWorkspace.QUEUED, RemoteWorkspace.STARTING, RemoteWorkspace.PREPARING))
                .forEach(workspace -> changeStatus(workspace.getId(), RemoteWorkspace.STOPPED, null));
    }

    private void startAsync(UUID workspaceId) {
        if (!starting.add(workspaceId)) {
            return;
        }
        Thread.ofVirtual().name("remote-start-" + workspaceId).start(() -> {
            try {
                start(workspaceId);
            } finally {
                starting.remove(workspaceId);
            }
        });
    }

    private void start(UUID workspaceId) {
        if (!acquireSlot(workspaceId)) {
            return;
        }
        var workspace = workspaces.findById(workspaceId).orElseThrow();
        try {
            if (!runtime.exists(workspace.getContainerName())) {
                var secret = devices.rotateRunnerSecret(workspace.getDeviceId());
                runtime.create(spec(workspace, secret));
            }
            runtime.start(workspace.getContainerName());
        } catch (ContainerException exception) {
            changeStatus(workspaceId, RemoteWorkspace.FAILED, exception.getMessage());
            return;
        }
        changeStatus(workspaceId, RemoteWorkspace.PREPARING, null);
        var credentials = users.gitHubCredentials(workspace.getUserId()).orElse(null);
        if (credentials == null) {
            changeStatus(workspaceId, RemoteWorkspace.FAILED, "GitHub repository access is missing");
            return;
        }
        var arguments = new LinkedHashMap<String, Object>();
        arguments.put("cloneUrl", "https://github.com/" + workspace.getRepository() + ".git");
        arguments.put("token", credentials.accessToken());
        arguments.put("branch", workspace.getBranch());
        arguments.put("baseBranch", workspace.getBaseBranch());
        arguments.put("authorName", credentials.login());
        arguments.put("authorEmail", credentials.login() + "@users.noreply.github.com");
        var result = runRepoOperation(workspace, PREPARE, arguments, properties.prepareTimeout());
        if (!result.succeeded()) {
            changeStatus(workspaceId, RemoteWorkspace.FAILED, errorMessage(result));
            return;
        }
        var baseBranch = String.valueOf(result.result().getOrDefault("baseBranch", workspace.getBaseBranch()));
        update(workspaceId, current -> {
            current.prepared(baseBranch, clock.instant());
            current.touch(clock.instant());
            return current;
        });
    }

    private boolean acquireSlot(UUID workspaceId) {
        var deadline = System.nanoTime() + properties.prepareTimeout().toNanos();
        while (!claimSlot(workspaceId)) {
            if (stopLeastRecentlyActiveIdle(workspaceId)) {
                continue;
            }
            if (System.nanoTime() > deadline) {
                changeStatus(
                        workspaceId,
                        RemoteWorkspace.FAILED,
                        "The server already runs " + properties.maxRunning()
                                + " busy session containers, try again later");
                return false;
            }
            if (!RemoteWorkspace.QUEUED.equals(
                    workspaces.findById(workspaceId).orElseThrow().getStatus())) {
                changeStatus(workspaceId, RemoteWorkspace.QUEUED, null);
            }
            try {
                Thread.sleep(SLOT_POLL_INTERVAL);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    private boolean claimSlot(UUID workspaceId) {
        synchronized (slots) {
            if (workspaces.countByStatusInAndIdNot(RUNNING_STATUSES, workspaceId) >= properties.maxRunning()) {
                return false;
            }
            changeStatus(workspaceId, RemoteWorkspace.STARTING, null);
            return true;
        }
    }

    private boolean stopLeastRecentlyActiveIdle(UUID workspaceId) {
        for (var candidate : workspaces.findByStatusOrderByLastActiveAtAsc(RemoteWorkspace.READY)) {
            if (candidate.getId().equals(workspaceId) || !isSessionIdle(candidate)) {
                continue;
            }
            try {
                runtime.stop(candidate.getContainerName());
                changeStatus(candidate.getId(), RemoteWorkspace.STOPPED, null);
                log.info("Stopped idle container {} to free a slot", candidate.getContainerName());
                return true;
            } catch (ContainerException exception) {
                log.warn("Could not stop container {}: {}", candidate.getContainerName(), exception.getMessage());
            }
        }
        return false;
    }

    private boolean isSessionIdle(RemoteWorkspace workspace) {
        var status =
                sessions.get(workspace.getUserId(), workspace.getSessionId()).status();
        return SessionStatus.IDLE.equals(status) || SessionStatus.ERROR.equals(status);
    }

    private void publish(UUID userId, UUID sessionId, UUID turnId) {
        synchronized (publishLocks.computeIfAbsent(sessionId, id -> new Object())) {
            var workspace = workspaces.findBySessionId(sessionId).orElse(null);
            var credentials = users.gitHubCredentials(userId).orElse(null);
            if (workspace == null || credentials == null) {
                return;
            }
            var arguments = new LinkedHashMap<String, Object>();
            arguments.put("branch", workspace.getBranch());
            arguments.put("message", commitMessage(sessionId, turnId));
            arguments.put("token", credentials.accessToken());
            var result = runRepoOperation(workspace, PUBLISH, arguments, PUBLISH_TIMEOUT);
            update(workspace.getId(), current -> {
                var now = clock.instant();
                if (result.succeeded()) {
                    if (Boolean.TRUE.equals(result.result().get("pushed"))) {
                        current.pushed(String.valueOf(result.result().get("commit")), now);
                    }
                    current.changeStatus(current.getStatus(), null, now);
                } else {
                    current.changeStatus(current.getStatus(), "Push failed: " + errorMessage(result), now);
                }
                current.touch(now);
                return current;
            });
        }
    }

    private String commitMessage(UUID sessionId, UUID turnId) {
        var subject = sessions.turnMessage(sessionId, turnId)
                .map(text -> text.strip().lines().findFirst().orElse("").strip())
                .filter(text -> !text.isEmpty())
                .orElse(DEFAULT_COMMIT_MESSAGE);
        return subject.length() <= MAX_COMMIT_SUBJECT
                ? subject
                : subject.substring(0, MAX_COMMIT_SUBJECT - 1).strip() + "…";
    }

    private ExecutionResult runRepoOperation(
            RemoteWorkspace workspace, String operation, Map<String, Object> arguments, Duration timeout) {
        var cancellation = new Cancellation();
        var timer = timers.schedule(cancellation::cancel, timeout.toMillis(), TimeUnit.MILLISECONDS);
        try {
            var result = execution
                    .getObject()
                    .execute(
                            request(workspace, operation, arguments, timeout),
                            new ExecutionListener() {},
                            cancellation);
            return cancellation.isCancelled() && !result.succeeded()
                    ? ExecutionResult.failure(
                            ExecutionResult.TIMEOUT, "runner_timeout", "The runner did not become ready in time")
                    : result;
        } finally {
            timer.cancel(false);
        }
    }

    private Map<String, Object> runFileOperation(
            UUID userId, UUID sessionId, String operation, Map<String, Object> arguments) {
        sessions.get(userId, sessionId);
        var workspace = owned(userId, sessionId);
        touch(sessionId);
        var result = runRepoOperation(workspace, operation, arguments, FILE_TIMEOUT);
        if (!result.succeeded()) {
            var code = result.error() == null
                    ? "remote_failed"
                    : String.valueOf(result.error().get("code"));
            throw ApiException.badRequest(code, errorMessage(result));
        }
        return result.result();
    }

    private ExecutionRequest request(
            RemoteWorkspace workspace, String operation, Map<String, Object> arguments, Duration timeout) {
        var session = sessions.get(workspace.getUserId(), workspace.getSessionId());
        return new ExecutionRequest(
                workspace.getSessionId(),
                session.projectId(),
                workspace.getDeviceId(),
                operation,
                arguments,
                false,
                timeout,
                null);
    }

    private ContainerSpec spec(RemoteWorkspace workspace, String secret) {
        var session = sessions.get(workspace.getUserId(), workspace.getSessionId());
        var environment = new LinkedHashMap<String, String>();
        environment.put("POINTER_API_URL", properties.apiUrl());
        environment.put("POINTER_DEVICE_ID", workspace.getDeviceId().toString());
        environment.put("POINTER_RUNNER_SECRET", secret);
        environment.put("POINTER_PROJECT_ID", session.projectId().toString());
        environment.put("POINTER_WORKSPACE", "/workspace/repo");
        return new ContainerSpec(
                workspace.getContainerName(),
                workspace.getVolumeName(),
                environment,
                Map.of("dev.pointer.session", workspace.getSessionId().toString()));
    }

    private RemoteWorkspace owned(UUID userId, UUID sessionId) {
        return workspaces
                .findBySessionId(sessionId)
                .filter(workspace -> workspace.getUserId().equals(userId))
                .orElseThrow(
                        () -> ApiException.notFound("remote_session_not_found", "The session is not on the server"));
    }

    private UserService.GitHubCredentials credentials(UUID userId) {
        return users.gitHubCredentials(userId)
                .orElseThrow(() -> ApiException.badRequest(
                        "github_repository_access_required",
                        "Allow Pointer to access your GitHub repositories in settings"));
    }

    private void ensureEnabled() {
        if (!properties.enabled()) {
            throw ApiException.unavailable("remote_execution_disabled", "Remote execution is not configured");
        }
    }

    private RemoteWorkspace changeStatus(UUID workspaceId, String status, String error) {
        return update(workspaceId, workspace -> {
            workspace.changeStatus(status, error, clock.instant());
            return workspace;
        });
    }

    private RemoteWorkspace update(UUID workspaceId, Function<RemoteWorkspace, RemoteWorkspace> change) {
        return transactions.execute(status ->
                workspaces.save(change.apply(workspaces.findById(workspaceId).orElseThrow())));
    }

    private static String errorMessage(ExecutionResult result) {
        return result.error() == null
                ? result.status()
                : String.valueOf(result.error().getOrDefault("message", ""));
    }

    private static String containerName(UUID sessionId) {
        return "pointer-session-" + sessionId;
    }

    private static String volumeName(UUID sessionId) {
        return "pointer-workspace-" + sessionId;
    }

    static RemoteDtos.RemoteWorkspaceResponse toResponse(RemoteWorkspace workspace) {
        return new RemoteDtos.RemoteWorkspaceResponse(
                workspace.getSessionId(),
                workspace.getRepository(),
                workspace.getBranch(),
                workspace.getBaseBranch(),
                workspace.getStatus(),
                workspace.getError(),
                workspace.getPushedCommit(),
                workspace.getPushedCommit() == null
                        ? null
                        : "https://github.com/" + workspace.getRepository() + "/tree/" + workspace.getBranch(),
                workspace.getPullRequestUrl(),
                workspace.getLastActiveAt());
    }
}
