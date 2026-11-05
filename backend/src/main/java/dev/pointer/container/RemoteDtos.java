package dev.pointer.container;

import dev.pointer.session.SessionDtos;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class RemoteDtos {

    private RemoteDtos() {}

    public record RemoteStatus(boolean enabled, boolean repositoryAccess) {}

    public record CreateRemoteSessionRequest(
            @Size(min = 1, max = 200) String title,
            @Pattern(regexp = "ask|plan|agent") String mode,
            UUID modelId,

            @Size(max = 200) @Pattern(regexp = "^[A-Za-z0-9._/-]*$") String baseBranch) {}

    public record RemoteSessionResponse(SessionDtos.SessionResponse session, RemoteWorkspaceResponse workspace) {}

    public record RemoteWorkspaceResponse(
            UUID sessionId,
            String repository,
            String branch,
            String baseBranch,
            String status,
            String error,
            String pushedCommit,
            String branchUrl,
            String pullRequestUrl,
            Instant lastActiveAt) {}

    public record PullRequestResponse(String url) {}

    public record RepositoryResponse(String fullName, String defaultBranch, boolean isPrivate) {}

    public record RemoteFile(String path, String content) {}

    public record RemoteFileSuggestions(List<String> paths) {}
}
