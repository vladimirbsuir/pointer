type Result = {
  status: "succeeded" | "failed" | "rejected";
  result: Record<string, unknown>;
  error: { code: string; message: string } | null;
  fileChanges: Record<string, unknown>[];
};

const files = new Map<string, string>([
  [
    "README.md",
    "# Demo project\n\nA tiny calculator used to test Pointer in the browser preview.\n",
  ],
  [
    "src/calculator.py",
    "def add(a, b):\n    return a + b\n\n\ndef subtract(a, b):\n    return a - b\n",
  ],
  ["src/main.py", "from calculator import add\n\nprint(add(2, 3))\n"],
  ["AGENTS.md", "Use type hints in Python code.\n"],
]);

function ok(result: Record<string, unknown>, fileChanges: Record<string, unknown>[] = []): Result {
  return { status: "succeeded", result, error: null, fileChanges };
}

function failed(code: string, message: string): Result {
  return { status: "failed", result: {}, error: { code, message }, fileChanges: [] };
}

function normalize(path: unknown) {
  return String(path ?? ".")
    .replace(/\\/g, "/")
    .replace(/^\.\/?/, "")
    .replace(/\/$/, "");
}

function diff(path: string, before: string, after: string) {
  const oldLines = before.split("\n");
  const newLines = after.split("\n");
  let start = 0;
  while (start < oldLines.length && oldLines[start] === newLines[start]) {
    start++;
  }
  let endOld = oldLines.length - 1;
  let endNew = newLines.length - 1;
  while (endOld >= start && endNew >= start && oldLines[endOld] === newLines[endNew]) {
    endOld--;
    endNew--;
  }
  const removed = oldLines.slice(start, endOld + 1);
  const added = newLines.slice(start, endNew + 1);
  const unified = [
    `--- ${path}`,
    `+++ ${path}`,
    `@@ -${start + 1},${removed.length} +${start + 1},${added.length} @@`,
    ...removed.map((line) => `-${line}`),
    ...added.map((line) => `+${line}`),
  ].join("\n");
  return { unified, linesAdded: added.length, linesRemoved: removed.length };
}

function change(path: string, after: string, dryRun: boolean): Result {
  const before = files.get(path);
  const { unified, linesAdded, linesRemoved } = diff(path, before ?? "", after);
  if (dryRun) {
    return ok({ preview: { path, unifiedDiff: unified, linesAdded, linesRemoved } });
  }
  files.set(path, after);
  return ok({ created: before === undefined }, [
    {
      path,
      changeKind: before === undefined ? "created" : "modified",
      before: before ?? null,
      after,
      linesAdded,
      linesRemoved,
    },
  ]);
}

function contentHash(content: string) {
  let hash = 0;
  for (const char of content) {
    hash = (Math.imul(hash, 31) + char.charCodeAt(0)) | 0;
  }
  return (hash >>> 0).toString(16);
}

function blockChunks(content: string) {
  const lines = content.split("\n");
  const starts = lines.flatMap((line, index) => (/^\S/.test(line) ? [index] : []));
  return starts
    .map((start, position) => {
      const end = (starts[position + 1] ?? lines.length) - 1;
      const text = lines
        .slice(start, end + 1)
        .join("\n")
        .trimEnd();
      const symbol = /^(?:def|class|function)\s+(\w+)/.exec(lines[start] ?? "")?.[1] ?? null;
      return { symbol, kind: "block", startLine: start + 1, endLine: end + 1, content: text };
    })
    .filter((chunk) => chunk.content.length > 0);
}

export function suggestFilesInBrowser(query: string) {
  const needle = query.toLowerCase();
  return [...files.keys()].filter((path) => path.toLowerCase().includes(needle)).sort();
}

export function readFileInBrowser(path: string) {
  const content = files.get(normalize(path));
  if (content === undefined) {
    throw { code: "file_not_found", message: "The file does not exist" };
  }
  return content;
}

export function executeInBrowser(
  operation: string,
  args: Record<string, unknown>,
  dryRun: boolean,
): Result {
  switch (operation) {
    case "pointer.project_context":
      return ok({
        rulesFile: { name: "AGENTS.md", content: files.get("AGENTS.md") },
        gitBranch: "main",
        isGitRepository: true,
      });
    case "list_directory": {
      const dir = normalize(args.path);
      const prefix = dir ? `${dir}/` : "";
      const names = new Map<string, string>();
      for (const path of files.keys()) {
        if (path.startsWith(prefix)) {
          const rest = path.slice(prefix.length).split("/");
          names.set(rest[0]!, rest.length > 1 ? "directory" : "file");
        }
      }
      return ok({
        entries: [...names].map(([name, kind]) => ({ name, kind })),
        truncated: false,
      });
    }
    case "read_file": {
      const content = files.get(normalize(args.path));
      if (content === undefined) {
        return failed("file_not_found", "The file does not exist");
      }
      const lines = content.split("\n");
      return ok({
        content: lines.map((line, index) => `${String(index + 1).padStart(6)}\t${line}`).join("\n"),
        totalLines: lines.length,
        truncated: false,
      });
    }
    case "glob": {
      const pattern = String(args.pattern ?? "*");
      const regex = new RegExp(
        `(^|/)${pattern
          .replace(/\*\*\//g, "(.*/)?")
          .replace(/\./g, "\\.")
          .replace(/\*/g, "[^/]*")}$`,
      );
      return ok({ paths: [...files.keys()].filter((path) => regex.test(path)), truncated: false });
    }
    case "grep": {
      const regex = new RegExp(String(args.pattern ?? ""), args.caseInsensitive ? "i" : "");
      const matches = [...files].flatMap(([path, content]) =>
        content
          .split("\n")
          .map((text, index) => ({ path, line: index + 1, text: text.trim() }))
          .filter((entry) => regex.test(entry.text)),
      );
      return ok({ matches, truncated: false });
    }
    case "pointer.index_scan":
      return ok({
        files: [...files].map(([path, content]) => ({ path, hash: contentHash(content) })),
        truncated: false,
      });
    case "pointer.index_chunks":
      return ok({
        files: (args.paths as string[]).map((path) => {
          const content = files.get(path);
          return content === undefined
            ? { path, missing: true }
            : { path, hash: contentHash(content), chunks: blockChunks(content) };
        }),
      });
    case "write_file":
      return change(normalize(args.path), String(args.content ?? ""), dryRun);
    case "edit_file": {
      const path = normalize(args.path);
      const before = files.get(path);
      if (before === undefined) {
        return failed("file_not_found", "The file does not exist");
      }
      const oldText = String(args.oldText ?? "");
      const count = oldText ? before.split(oldText).length - 1 : 0;
      if (count === 0) {
        return failed("text_not_found", "oldText was not found in the file");
      }
      if (count > 1 && !args.replaceAll) {
        return failed("text_not_unique", `oldText occurs ${count} times`);
      }
      const after = args.replaceAll
        ? before.split(oldText).join(String(args.newText ?? ""))
        : before.replace(oldText, String(args.newText ?? ""));
      return change(path, after, dryRun);
    }
    case "pointer.restore_file": {
      const path = normalize(args.path);
      const current = files.get(path) ?? null;
      if (!args.force && current !== (args.expectedContent ?? null)) {
        return failed("file_modified", "The file changed after the agent edited it");
      }
      if (dryRun) {
        return ok({ restored: false });
      }
      if (args.content === null || args.content === undefined) {
        files.delete(path);
      } else {
        files.set(path, String(args.content));
      }
      return ok({ restored: true });
    }
    case "web_fetch":
      return ok({
        url: String(args.url),
        status: 200,
        contentType: "text/html",
        content: `(browser preview) simulated page at ${String(args.url)}`,
        truncated: false,
      });
    case "pointer.worktree_create":
      return ok({ baseBranch: "main" });
    case "pointer.worktree_status":
      return ok({
        path: "C:/Users/dev/AppData/Roaming/pointer/worktrees/preview",
        branch: "pointer/preview",
        baseBranch: String(args.baseBranch),
        commitsAhead: 1,
        uncommittedFiles: ["src/App.tsx"],
        baseCheckedOut: true,
      });
    case "pointer.worktree_merge":
      return ok({ merged: true, commits: 2 });
    case "pointer.worktree_discard":
      return ok({ discarded: true });
    case "run_command":
      return ok({
        exitCode: 0,
        output: `(browser preview) simulated: ${String(args.command)}\n`,
        truncated: false,
      });
    default:
      return {
        status: "rejected",
        result: {},
        error: { code: "unknown_operation", message: operation },
        fileChanges: [],
      };
  }
}
