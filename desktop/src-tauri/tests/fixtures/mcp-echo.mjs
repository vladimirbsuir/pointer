import { createInterface } from "node:readline";

const tools = [
  {
    name: "echo",
    description: "Echo the text back",
    inputSchema: { type: "object", properties: { text: { type: "string" } }, required: ["text"] },
    annotations: { readOnlyHint: true },
  },
];

function reply(id, result) {
  process.stdout.write(JSON.stringify({ jsonrpc: "2.0", id, result }) + "\n");
}

createInterface({ input: process.stdin }).on("line", (line) => {
  const message = JSON.parse(line);
  if (message.id === undefined) {
    return;
  }
  switch (message.method) {
    case "initialize":
      reply(message.id, {
        protocolVersion: message.params.protocolVersion,
        capabilities: { tools: {} },
        serverInfo: { name: "echo", version: "1.0.0" },
      });
      break;
    case "tools/list":
      reply(message.id, { tools });
      break;
    case "tools/call":
      reply(message.id, {
        content: [{ type: "text", text: `echo: ${message.params.arguments.text}` }],
        isError: false,
      });
      break;
    default:
      process.stdout.write(
        JSON.stringify({ jsonrpc: "2.0", id: message.id, error: { code: -32601, message: "unknown" } }) + "\n",
      );
  }
});
