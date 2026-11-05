package dev.pointer.container;

import dev.pointer.device.DeviceService;
import dev.pointer.execution.ExecutionService;
import dev.pointer.project.ProjectService;
import dev.pointer.session.SessionService;
import dev.pointer.user.UserService;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ContainerProperties.class)
class ContainerConfiguration {

    @Bean
    @ConditionalOnMissingBean
    ContainerRuntime containerRuntime(ContainerProperties properties, JsonMapper json) {
        return new DockerContainerRuntime(properties, json);
    }

    @Bean
    @ConditionalOnMissingBean
    GitHubRepositories gitHubRepositories(ContainerProperties properties) {
        return new RestGitHubRepositories(properties.githubApiUrl());
    }

    @Bean
    RemoteSessionService remoteSessionService(
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
        return new RemoteSessionService(
                properties,
                runtime,
                workspaces,
                projects,
                sessions,
                devices,
                users,
                gitHub,
                execution,
                transactions,
                clock);
    }
}
