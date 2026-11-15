package dev.pointer.automation;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pointer.container.GitHubRepositories;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class IssuePromptTests {

    @Test
    void promptContainsTheIssueItsCommentsAndTheInstructions() {
        var prompt = IssuePrompt.build(
                issue("Crash on empty input", "Steps: run with no arguments"),
                List.of(new GitHubRepositories.IssueComment("alice", "Same on Linux")));

        assertThat(prompt)
                .startsWith("Implement issue #7: Crash on empty input\n")
                .contains("Do not touch CI configuration")
                .contains("# Issue #7: Crash on empty input")
                .contains("Steps: run with no arguments")
                .contains("**alice**:\nSame on Linux");
    }

    @Test
    void emptyBodyIsMarkedAndOnlyTwentyCommentsAreKept() {
        var comments = IntStream.rangeClosed(1, 30)
                .mapToObj(index -> new GitHubRepositories.IssueComment("user" + index, "comment " + index))
                .toList();

        var prompt = IssuePrompt.build(issue("Title", " "), comments);

        assertThat(prompt).contains("(no description)").contains("**user20**").doesNotContain("**user21**");
    }

    @Test
    void longIssuesAreTruncatedToTheLimit() {
        var prompt = IssuePrompt.build(issue("Title", "x".repeat(50_000)), List.of());
        var issueText = prompt.substring(prompt.indexOf("# Issue #7"));

        assertThat(issueText).hasSize(IssuePrompt.MAX_LENGTH).endsWith("[truncated]");
    }

    @Test
    void checkOutputKeepsTheLastLines() {
        var output = String.join(
                "\n",
                IntStream.rangeClosed(1, 500).mapToObj(index -> "line " + index).toList());

        var tail = IssuePrompt.tail(output);

        assertThat(tail.lines()).hasSize(IssuePrompt.MAX_OUTPUT_LINES);
        assertThat(tail).startsWith("line 301").endsWith("line 500");
        assertThat(IssuePrompt.tail("y".repeat(100_000))).hasSize(IssuePrompt.MAX_OUTPUT_LENGTH);
        assertThat(IssuePrompt.checkFailed(7, "npm test", "exit code 1", tail))
                .startsWith("Fix the failing check for issue #7\n")
                .contains("The check command `npm test` failed with exit code 1.")
                .endsWith("Fix the cause.");
    }

    private static GitHubRepositories.Issue issue(String title, String body) {
        return new GitHubRepositories.Issue(7, title, body, "octo", "https://github.com/octo/demo/issues/7", false);
    }
}
