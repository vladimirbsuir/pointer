import type { SlashCommand } from "@/shared/api/types";

const COMMAND_PATTERN = /^\/([a-z0-9][a-z0-9-]*)(?:\s+([\s\S]*))?$/;
const ARGUMENTS_PLACEHOLDER = "$ARGUMENTS";

export function findCommandQuery(text: string, caret: number): string | null {
  const match = /^\/([a-z0-9-]*)$/.exec(text.slice(0, caret));
  return match ? (match[1] ?? "") : null;
}

export function matchingCommands(commands: SlashCommand[], query: string) {
  return commands.filter((command) => command.name.startsWith(query));
}

export function expandCommand(text: string, commands: SlashCommand[]): string {
  const match = COMMAND_PATTERN.exec(text.trim());
  const command = match && commands.find((candidate) => candidate.name === match[1]);
  if (!command) {
    return text;
  }
  const args = (match[2] ?? "").trim();
  if (command.prompt.includes(ARGUMENTS_PLACEHOLDER)) {
    return command.prompt.split(ARGUMENTS_PLACEHOLDER).join(args).trim();
  }
  return args ? `${command.prompt.trim()}\n\n${args}` : command.prompt.trim();
}
