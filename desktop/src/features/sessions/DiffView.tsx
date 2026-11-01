import { cn } from "@/shared/lib/utils";

export function DiffView({ diff }: { diff: string }) {
  const lines = diff
    .split("\n")
    .filter((line) => !line.startsWith("---") && !line.startsWith("+++"));

  return (
    <pre className="max-h-80 overflow-auto rounded-md border bg-muted/40 py-2 font-mono text-xs leading-relaxed">
      {lines.map((line, index) => (
        <div
          key={index}
          className={cn(
            "px-3 whitespace-pre",
            line.startsWith("+") && "bg-emerald-500/10 text-emerald-700 dark:text-emerald-400",
            line.startsWith("-") && "bg-red-500/10 text-red-700 dark:text-red-400",
            line.startsWith("@@") && "text-muted-foreground",
          )}
        >
          {line || " "}
        </div>
      ))}
    </pre>
  );
}
