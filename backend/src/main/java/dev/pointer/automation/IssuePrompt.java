package dev.pointer.automation;

import dev.pointer.container.GitHubRepositories;
import java.util.List;

final class IssuePrompt {

    static final int MAX_COMMENTS = 20;
    static final int MAX_LENGTH = 12_000;
    static final int MAX_OUTPUT_LINES = 200;
    static final int MAX_OUTPUT_LENGTH = 12_000;

    private static final String TRUNCATED = "\n[truncated]";
    private static final String INSTRUCTIONS = """
            Work in the repository you are in and follow these rules.

            - Read the relevant code before changing it and keep the changes minimal and focused on the issue.
            - Add or update tests when the issue changes behaviour, and run the project's tests.
            - Do not touch CI configuration unless the issue asks for it.
            - Do not commit, push or open a pull request: this happens automatically after your turn.
            - Finish with a short summary of what you changed; it becomes the pull request description.
            - If the issue cannot be implemented without more information, change nothing and ask your question.""";

    private IssuePrompt() {}

    static String build(GitHubRepositories.Issue issue, List<GitHubRepositories.IssueComment> comments) {
        var issueText = new StringBuilder("# Issue #")
                .append(issue.number())
                .append(": ")
                .append(issue.title())
                .append("\n\n");
        issueText.append(
                issue.body().isBlank() ? "(no description)" : issue.body().strip());
        var shown = comments.stream().limit(MAX_COMMENTS).toList();
        if (!shown.isEmpty()) {
            issueText.append("\n\n## Comments\n");
            for (var comment : shown) {
                issueText
                        .append("\n**")
                        .append(comment.author())
                        .append("**:\n")
                        .append(comment.body().strip())
                        .append('\n');
            }
        }
        return "Implement issue #" + issue.number() + ": " + issue.title().strip() + "\n\n" + INSTRUCTIONS + "\n\n"
                + truncate(issueText.toString().strip(), MAX_LENGTH);
    }

    static String checkFailed(int issueNumber, String command, String outcome, String output) {
        return "Fix the failing check for issue #" + issueNumber + "\n\nThe check command `" + command
                + "` failed with " + outcome + ". Output (last " + MAX_OUTPUT_LINES + " lines):\n\n```\n" + output
                + "\n```\n\nFix the cause.";
    }

    static String resume(int issueNumber) {
        return "Continue the work on issue #" + issueNumber + "\n\nThe server restarted while you were working on"
                + " the issue. Continue from where you stopped and finish with a short summary of what you changed.";
    }

    static String tail(String output) {
        var lines = output.strip().lines().toList();
        var kept =
                lines.size() > MAX_OUTPUT_LINES ? lines.subList(lines.size() - MAX_OUTPUT_LINES, lines.size()) : lines;
        var text = String.join("\n", kept);
        return text.length() > MAX_OUTPUT_LENGTH ? text.substring(text.length() - MAX_OUTPUT_LENGTH) : text;
    }

    static String truncate(String text, int limit) {
        return text.length() <= limit ? text : text.substring(0, limit - TRUNCATED.length()) + TRUNCATED;
    }
}
